import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('tvcheck', Path(__file__).parents[1] / 'tv-check.py')
tv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tv)


class Parsers(unittest.TestCase):
    def test_mem_modern(self):
        self.assertEqual(tv.parse_meminfo('TOTAL PSS: 1234 TOTAL RSS: 5678'), {'pss_kb': 1234, 'rss_kb': 5678})

    def test_mem_legacy(self):
        self.assertEqual(tv.parse_meminfo('    TOTAL    1234    99\n')['pss_kb'], 1234)

    def test_mem_missing(self):
        self.assertIsNone(tv.parse_meminfo('No process found')['pss_kb'])

    def test_frames(self):
        result = tv.parse_gfx('Total frames rendered: 100\nJanky frames: 4 (4%)\n95th percentile: 20ms')
        self.assertEqual(result['janky_percent'], 4)
        self.assertEqual(result['p95_ms'], 20)

    def test_zero_frames_not_zero_jank_rate(self):
        self.assertIsNone(tv.parse_gfx('Total frames rendered: 0\nJanky frames: 0')['janky_percent'])

    def test_invalid_counters(self):
        result = tv.parse_gfx('Total frames rendered: 10\nJanky frames: 12')
        self.assertIsNone(result['janky_percent'])
        self.assertIn('warning', result)

    def test_unsupported_not_success(self):
        self.assertIsNone(tv.parse_gfx('Permission Denial')['total_frames'])

    def test_cold_launch(self):
        result = tv.parse_launch('Status: ok\nLaunchState: COLD\nTotalTime: 210\nWaitTime: 240')
        self.assertTrue(result['cold_confirmed'])
        self.assertEqual(result['total_time_ms'], 210)

    def test_hot_not_cold(self):
        self.assertFalse(tv.parse_launch('Status: ok\nLaunchState: HOT\nTotalTime: 0')['cold_confirmed'])

    def test_warning_not_cold(self):
        self.assertFalse(tv.parse_launch('Warning: Activity not started\nLaunchState: COLD')['cold_confirmed'])

    def test_no_launch_data(self):
        self.assertIsNone(tv.parse_launch('Permission denied')['total_time_ms'])

    def test_current_focus_only(self):
        raw = 'mCurrentFocus=other.package\nmFocusedApp=' + tv.PACKAGE
        self.assertFalse(tv.launcher_focused(raw))

    def test_focus_filter(self):
        raw = 'unrelated secret\nmCurrentFocus=' + tv.PACKAGE + '\nprivate URL\nmFocusedApp=' + tv.PACKAGE
        filtered = tv.focus_lines(raw)
        self.assertNotIn('secret', filtered)
        self.assertNotIn('URL', filtered)
        self.assertTrue(tv.launcher_focused(filtered))

    def test_disabled_filter(self):
        parsed = tv.disabled_sber('package:ru.sberdevices.starlauncher\npackage:com.other.app')
        self.assertTrue(parsed['ru.sberdevices.starlauncher'])
        self.assertFalse(parsed['ru.sberdevices.music'])
        self.assertNotIn('com.other.app', parsed)


class CecAndComparison(unittest.TestCase):
    def test_cec_ps5(self):
        raw = 'CEC: logical_address: 0x04 display_name: PlayStation 5 power_status: 1 physical_address: 0x2000 port_id: 2'
        self.assertEqual(tv.parse_hdmi_cec(raw), [{'port': 2, 'name': 'PlayStation 5',
            'reported_power_code': 1, 'reported_power': 'Режим ожидания'}])

    def test_cec_dedup_and_tv_filter(self):
        device = 'display_name: Box power_status: 0 physical_address: 0x1000 port_id: 1'
        raw = 'display_name: TV power_status: 0 physical_address: 0x0000 port_id: 0\n' + device + '\n' + device
        self.assertEqual(len(tv.parse_hdmi_cec(raw)), 1)

    def test_cec_unknown_and_invalid(self):
        raw = 'display_name: Device power_status: -1 physical_address: 0xffff port_id: 2'
        self.assertEqual(tv.parse_hdmi_cec(raw), [])
        self.assertEqual(tv.parse_hdmi_cec(raw.replace('0xffff','0x2000'))[0]['reported_power'], 'Неизвестно')

    @staticmethod
    def archive(path, version=1, frames=200, cold=True):
        import zipfile
        data = {'format':'sashatv-diagnostics', 'version': version,
                'stages':[{'label':'after-navigation', 'pid':'1', 'memory':{'pss_kb':1000}, 'frames':{}}],
                'launches':[{'cold_confirmed':cold, 'total_time_ms':200}]}
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('report/summary.json', json.dumps(data))
            archive.writestr('report/after-navigation/frames.txt',
                            'Total frames rendered: {}\nJanky frames: 10\n50th gpu percentile: 12ms'.format(frames))

    def test_old_new_comparison(self):
        with tempfile.TemporaryDirectory() as root:
            a, b = Path(root)/'old.zip', Path(root)/'new.zip'
            self.archive(a); self.archive(b, 2)
            result = tv.compare_reports(a, b)
            self.assertIn('GPU p50, мс | 12 | 12', result)
            self.assertIn('Задержанные кадры, % | 5.0 | 5.0', result)

    def test_low_samples_and_hot_ignored(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)/'small.zip';self.archive(path, frames=10, cold=False)
            self.assertIsNone(tv.report_metrics(path)['cold_median_ms'])
            self.assertIn('Недостаточно кадров', tv.compare_reports(path, path))

    def test_bad_report_shape(self):
        import zipfile
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)/'bad.zip'
            with zipfile.ZipFile(path, 'w') as archive: archive.writestr('summary.json','[]')
            with self.assertRaises(ValueError): tv.report_metrics(path)


class Collection(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.adb = self.root / 'adb'
        self.adb.write_text('''#!/usr/bin/env python3
import os,sys
cmd=sys.argv[3:]
s=' '.join(cmd)
if cmd==['get-state']:print('device')
elif 'pidof' in s:print('234')
elif 'dumpsys window' in s:
 print('mCurrentFocus=' + ('other.app' if os.getenv('FOREIGN_FOCUS') else 'ru.sasha.tvlauncher'))
 print('unrelated private value')
elif 'dumpsys meminfo' in s:print('TOTAL PSS: 1234 TOTAL RSS: 3000')
elif 'gfxinfo' in s:print('Total frames rendered: 50\\nJanky frames: 2')
elif 'am start' in s:print('Status: ok\\nLaunchState: COLD\\nTotalTime: 230')
elif 'pm list packages' in s:print('package:ru.sberdevices.starlauncher\\npackage:other.private.app')
elif 'dumpsys package' in s:print('versionName=0.7.0')
else:print('ok')
''')
        self.adb.chmod(0o755)
        self.collector = tv.Collector(str(self.adb), '192.0.2.10:5555', self.root)

    def tearDown(self):
        self.temp.cleanup()

    def test_snapshot_is_read_only_and_scoped(self):
        stage = self.collector.snapshot('snapshot')
        self.assertEqual(stage['memory']['pss_kb'], 1234)
        for record in self.collector.records:
            args = record['args']
            self.assertNotIn('run-as', args)
            self.assertNotIn('input', args)
            self.assertNotIn('am', args)
            if 'logcat' in args:
                self.assertIn('--pid=234', args)
        self.assertNotIn('private', (self.root / 'snapshot' / 'focus.txt').read_text())
        self.assertNotIn('other.private.app', (self.root / 'snapshot' / 'disabled-sber.json').read_text())

    def test_measure_only_restarts_launcher_and_uses_arrows_back(self):
        with patch.object(tv.time, 'sleep'):
            result = self.collector.measure()
        self.assertEqual(len(result), 3)
        commands = [r['args'] for r in self.collector.records]
        starts = [c for c in commands if 'am' in c]
        self.assertEqual(len(starts), 3)
        self.assertTrue(all(c[-1] == tv.COMPONENT and '-S' in c for c in starts))
        keys = [c[-1] for c in commands if 'input' in c]
        self.assertTrue(keys)
        self.assertTrue(set(keys) <= {'KEYCODE_DPAD_DOWN','KEYCODE_DPAD_LEFT','KEYCODE_DPAD_RIGHT','KEYCODE_BACK'})
        self.assertNotIn('KEYCODE_DPAD_CENTER', keys)

    def test_foreign_focus_stops_input(self):
        with patch.dict(os.environ, {'FOREIGN_FOCUS':'1'}), patch.object(tv.time, 'sleep'):
            with self.assertRaises(RuntimeError):
                self.collector.measure()
        self.assertFalse(any('input' in r['args'] for r in self.collector.records))

    def test_partial_report_kept(self):
        path = self.collector.finish([], 'unavailable')
        self.assertTrue(path.is_file())
        self.assertEqual(json.loads((self.root / 'summary.json').read_text())['problem'], 'unavailable')
        # Archive sits beside the temporary report directory.
        path.unlink()


if __name__ == '__main__':
    unittest.main()
