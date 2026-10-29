#!/usr/bin/env python3
"""Bounded ADB measurements for Luma. snapshot is read-only; measure restarts only Luma."""
import argparse
import os
from datetime import datetime
import json
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import time
import zipfile

PACKAGE = 'ru.sasha.tvlauncher'
COMPONENT = PACKAGE + '/.MainActivity'
DISABLED_SBER = {
    'ru.sberdevices.starlauncher', 'ru.sberdevices.smarthome.promo',
    'ru.sberdevices.survey', 'ru.sberdevices.music', 'ru.sberdevices.sberplay', 'ru.sberdevices.videos'
}


def number(pattern, text):
    match = re.search(pattern, text, re.MULTILINE)
    return int(match.group(1)) if match else None


def parse_meminfo(raw):
    pss = number(r'^\s*TOTAL PSS:\s*(\d+)', raw)
    if pss is None:
        pss = number(r'^\s*TOTAL\s+(\d+)\s', raw)
    return {'pss_kb': pss, 'rss_kb': number(r'TOTAL RSS:\s*(\d+)', raw)}


def parse_gfx(raw):
    total = number(r'Total frames rendered:\s*(\d+)', raw)
    janky = number(r'Janky frames:\s*(\d+)', raw)
    if total is not None and janky is not None and janky > total:
        return {'total_frames': total, 'janky_frames': None, 'janky_percent': None,
                'warning': 'Счётчики несовместимы; процент не вычислен.'}
    return {'total_frames': total, 'janky_frames': janky,
            'janky_percent': round(100 * janky / total, 2) if total and janky is not None else None,
            'p50_ms': number(r'50th percentile:\s*(\d+)ms', raw),
            'p90_ms': number(r'90th percentile:\s*(\d+)ms', raw),
            'p95_ms': number(r'95th percentile:\s*(\d+)ms', raw),
            'p99_ms': number(r'99th percentile:\s*(\d+)ms', raw),
            'gpu_p50_ms': number(r'50th gpu percentile:\s*(\d+)ms', raw),
            'gpu_p95_ms': number(r'95th gpu percentile:\s*(\d+)ms', raw),
            'slow_ui_frames': number(r'Number Slow UI thread:\s*(\d+)', raw),
            'slow_draw_frames': number(r'Number Slow issue draw commands:\s*(\d+)', raw),
            'slow_upload_frames': number(r'Number Slow bitmap uploads:\s*(\d+)', raw)}


def parse_launch(raw):
    state = re.search(r'^LaunchState:\s*(\S+)', raw, re.MULTILINE)
    status = re.search(r'^Status:\s*(\S+)', raw, re.MULTILINE)
    launch_state = state.group(1) if state else None
    warning = 'Activity not started' in raw
    return {'status': status.group(1) if status else None, 'launch_state': launch_state,
            'this_time_ms': number(r'^ThisTime:\s*(\d+)', raw),
            'total_time_ms': number(r'^TotalTime:\s*(\d+)', raw),
            'wait_time_ms': number(r'^WaitTime:\s*(\d+)', raw),
            'cold_confirmed': launch_state == 'COLD' and not warning}


def focus_lines(raw):
    return '\n'.join(line for line in raw.splitlines() if re.search(r'mCurrentFocus=|mFocusedApp=', line))


def launcher_focused(raw):
    return any('mCurrentFocus=' in line and PACKAGE in line for line in raw.splitlines())


def disabled_sber(raw):
    listed = {line.strip()[8:] if line.strip().startswith('package:') else line.strip() for line in raw.splitlines()}
    return {name: name in listed for name in sorted(DISABLED_SBER)}


def parse_hdmi_cec(raw):
    devices, seen = [], set()
    pattern = re.compile(r'display_name:\s*(.*?)\s+power_status:\s*(-?\d+)\s+'
                         r'physical_address:\s*(0x[0-9a-fA-F]+)\s+port_id:\s*(\d+)')
    power = {0: 'Включено', 1: 'Режим ожидания', 2: 'Включается', 3: 'Выключается'}
    for line in raw.splitlines():
        match = pattern.search(line)
        if not match:
            continue
        name, status, physical, port = match.groups()
        port, status = int(port), int(status)
        name = re.sub(r'[\x00-\x1f\x7f]', ' ', name).strip()[:80]
        key = (port, physical.lower(), name)
        if port <= 0 or physical.lower() == '0xffff' or key in seen:
            continue
        seen.add(key)
        devices.append({'port': port, 'name': name, 'reported_power_code': status,
                        'reported_power': power.get(status, 'Неизвестно')})
    return sorted(devices, key=lambda item: (item['port'], item['name']))


def report_metrics(path):
    # Read bounded members directly; never extract paths from a supplied archive.
    with zipfile.ZipFile(path) as archive:
        summaries = [name for name in archive.namelist() if name.endswith('/summary.json') or name == 'summary.json']
        if len(summaries) != 1 or archive.getinfo(summaries[0]).file_size > 2_000_000:
            raise ValueError('Неоднозначный или слишком большой отчёт')
        report = json.loads(archive.read(summaries[0]).decode('utf-8'))
        if not isinstance(report, dict):
            raise ValueError('Некорректная структура отчёта')
        if report.get('format') != 'sashatv-diagnostics' or report.get('version') not in (1, 2):
            raise ValueError('Неизвестный формат отчёта')
        stages = report.get('stages', [])
        if not isinstance(stages, list) or len(stages) > 50 or not all(isinstance(item, dict) for item in stages):
            raise ValueError('Некорректные этапы отчёта')
        stage = next((item for item in stages if item.get('label') == 'after-navigation'), None)
        if stage is None:
            raise ValueError('В отчёте нет завершённого замера навигации')
        frames = stage.get('frames', {})
        frame_files = [name for name in archive.namelist() if name.endswith('/after-navigation/frames.txt')]
        if len(frame_files) == 1 and archive.getinfo(frame_files[0]).file_size <= 2_000_000:
            frames = parse_gfx(archive.read(frame_files[0]).decode('utf-8', errors='replace'))
        launches = [item['total_time_ms'] for item in report.get('launches', [])
                    if item.get('cold_confirmed') and isinstance(item.get('total_time_ms'), (int, float))]
        return {'frames': frames, 'cold_median_ms': statistics.median(launches) if launches else None,
                'pss_kb': stage.get('memory', {}).get('pss_kb'), 'pid': stage.get('pid')}


def compare_reports(baseline, candidate):
    old, new = report_metrics(baseline), report_metrics(candidate)
    lines = ['Luma — сравнение одинакового сценария навигации', '',
             'Показатель | До | После',
             'Холодный запуск процесса, медиана мс | {} | {}'.format(old['cold_median_ms'], new['cold_median_ms']),
             'PSS после навигации, КБ | {} | {}'.format(old['pss_kb'], new['pss_kb'])]
    for key, title in [('total_frames', 'Кадры'), ('janky_percent', 'Задержанные кадры, %'),
                       ('p50_ms', 'Кадр p50, мс'), ('p95_ms', 'Кадр p95, мс'),
                       ('gpu_p50_ms', 'GPU p50, мс'), ('gpu_p95_ms', 'GPU p95, мс')]:
        lines.append('{} | {} | {}'.format(title, old['frames'].get(key), new['frames'].get(key)))
    lines += ['', 'Сравнение имеет смысл при одинаковых VPN, анимациях, подборке и фоновых приложениях.',
              'Это два замера, а не доказательство причины или утечки. Небольшое число кадров ненадёжно.']
    if min(old['frames'].get('total_frames') or 0, new['frames'].get('total_frames') or 0) < 120:
        lines.append('Недостаточно кадров хотя бы в одном отчёте — не делай вывод о плавности.')
    return '\n'.join(lines)


class Collector:
    def __init__(self, adb, serial, directory):
        self.adb, self.serial, self.directory = adb, serial, directory
        self.records = []
        self.stages = []
        self.launches = []

    def run(self, *args, timeout=20):
        try:
            result = subprocess.run([self.adb, '-s', self.serial, *args], capture_output=True,
                                    text=True, encoding='utf-8', errors='replace', timeout=timeout)
            out = result.stdout
            if result.stderr:
                out += '\n[stderr]\n' + result.stderr
            record = {'args': list(args), 'exit_code': result.returncode, 'timeout': False}
        except subprocess.TimeoutExpired:
            out = '[command timed out]'
            record = {'args': list(args), 'exit_code': None, 'timeout': True}
        self.records.append(record)
        return out[:2_000_000]

    def shell(self, *args, **kwargs):
        return self.run('shell', *args, **kwargs)

    def connect(self):
        state = self.run('get-state')
        if state.strip() != 'device' and ':' in self.serial:
            # Use only the explicitly configured device; never search the LAN.
            try:
                subprocess.run([self.adb, 'connect', self.serial], capture_output=True, timeout=10)
            except subprocess.TimeoutExpired:
                pass
            state = self.run('get-state')
        if state.strip() != 'device':
            raise RuntimeError('Телевизор не доступен по ADB: ' + self.serial)

    def save(self, folder, filename, text):
        (folder / filename).write_text(text, encoding='utf-8')
        return text

    def snapshot(self, label):
        print('Собираю:', label, flush=True)
        folder = self.directory / label
        folder.mkdir()
        self.connect()
        focus = self.save(folder, 'focus.txt', focus_lines(self.shell('dumpsys', 'window')))
        pid = self.shell('pidof', PACKAGE).strip()
        pid = pid if re.fullmatch(r'\d+', pid) else None
        memory = self.save(folder, 'memory.txt', self.shell('dumpsys', 'meminfo', PACKAGE))
        gfx = self.save(folder, 'frames.txt', self.shell('dumpsys', 'gfxinfo', PACKAGE, 'framestats'))
        self.save(folder, 'tv-input.txt', self.shell('dumpsys', 'tv_input'))
        cec = self.save(folder, 'hdmi-cec.txt', self.shell('dumpsys', 'hdmi_control'))
        self.save(folder, 'uptime.txt', self.shell('cat', '/proc/uptime'))
        self.save(folder, 'display-size.txt', self.shell('wm', 'size'))
        self.save(folder, 'display-density.txt', self.shell('wm', 'density'))
        self.save(folder, 'default-home.txt', self.shell('cmd', 'package', 'resolve-activity', '--brief',
                  '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))
        disabled = self.shell('pm', 'list', 'packages', '-d')
        sber = disabled_sber(disabled)
        if self.records[-1]['exit_code'] != 0 or re.search(r'Permission (Denial|denied)|not found', disabled, re.I):
            sber = {name: None for name in sorted(DISABLED_SBER)}
        self.save(folder, 'disabled-sber.json', json.dumps(sber, ensure_ascii=False, indent=2))
        for field in ['ro.product.model', 'ro.product.device', 'ro.build.version.release', 'ro.build.version.sdk']:
            self.save(folder, field + '.txt', self.shell('getprop', field))
        package = self.shell('dumpsys', 'package', PACKAGE)
        version = '\n'.join(line.strip() for line in package.splitlines()
                            if re.search(r'\bversionCode=|\bversionName=', line))
        self.save(folder, 'version.txt', version)
        if pid:
            self.save(folder, 'lifecycle.txt', self.run('logcat', '-d', '-t', '400', '--pid=' + pid,
                      '-v', 'threadtime', '-s', 'SashaTVDiag:I', 'AndroidRuntime:E'))
        else:
            self.save(folder, 'lifecycle.txt', 'Процесс лаунчера отсутствует; логи других приложений не собирались.')
        stage = {'label': label, 'pid': pid, 'launcher_focused': launcher_focused(focus),
                 'memory': parse_meminfo(memory), 'frames': parse_gfx(gfx), 'disabled_sber': sber,
                 'app_version': version, 'cec_devices': parse_hdmi_cec(cec)}
        self.stages.append(stage)
        return stage

    def ensure_focus(self):
        if not launcher_focused(focus_lines(self.shell('dumpsys', 'window'))):
            raise RuntimeError('Фокус находится вне Luma. Автоматические нажатия остановлены.')

    def measure(self):
        print('Три запуска Luma с перезапуском процесса; затем стрелки без OK. ТВ должен быть свободен.', flush=True)
        launches = self.launches
        for index in range(3):
            raw = self.shell('am', 'start', '-S', '-W', '-n', COMPONENT, timeout=30)
            self.save(self.directory, 'launch-' + str(index + 1) + '.txt', raw)
            launch = parse_launch(raw)
            launches.append(launch)
            if launch['status'] != 'ok' or self.records[-1]['exit_code'] != 0 or 'Error:' in raw:
                raise RuntimeError('Запуск не подтверждён; смотри launch-' + str(index + 1) + '.txt')
            time.sleep(1.5)
        self.ensure_focus()
        self.snapshot('before-navigation')
        reset = self.shell('dumpsys', 'gfxinfo', PACKAGE, 'reset')
        if self.records[-1]['exit_code'] != 0 or re.search(r'Permission (Denial|denied)|No process found|not found', reset, re.I):
            raise RuntimeError('Не удалось сбросить счётчики кадров; измерение навигации не запускалось.')
        for key in ['KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_DOWN']:
            self.ensure_focus()
            self.shell('input', 'keyevent', key)
            if self.records[-1]['exit_code'] != 0:
                raise RuntimeError('Не удалось отправить стрелку пульта.')
            time.sleep(.2)
        for _ in range(3):
            for key in ['KEYCODE_DPAD_RIGHT'] * 7 + ['KEYCODE_DPAD_LEFT'] * 7:
                self.ensure_focus()
                self.shell('input', 'keyevent', key)
                if self.records[-1]['exit_code'] != 0:
                    raise RuntimeError('Не удалось отправить стрелку пульта.')
                time.sleep(.08)
        self.ensure_focus()
        self.shell('input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(1)
        self.snapshot('after-navigation')
        return launches

    def finish(self, launches, problem=None):
        report = {'format': 'sashatv-diagnostics', 'version': 2, 'stages': self.stages,
                  'launches': launches, 'commands': self.records, 'problem': problem,
                  'limitations': [
                      'am start -S перезапускает приложение, а не весь телевизор. LaunchState показывает фактический тип запуска.',
                      'Статистика gfxinfo относится к нарисованным кадрам; это не средний FPS на неподвижном экране.',
                      'Два замера PSS не доказывают утечку памяти; PID и условия должны совпадать.',
                      'Снимки HDMI показывают сведения прошивки, не доказывают наличие видеосигнала или успешное переключение.',
                      'Отказ команды или отсутствие чисел означает недоступные данные, а не успешную проверку.']}
        self.save(self.directory, 'summary.json', json.dumps(report, ensure_ascii=False, indent=2))
        lines = ['Luma — отчёт диагностики', '']
        for stage in self.stages:
            lines += [stage['label'] + ':', '  PID: ' + str(stage['pid']),
                      '  PSS, КБ: ' + str(stage['memory']['pss_kb']),
                      '  Кадры: ' + str(stage['frames'].get('total_frames')),
                      '  Задержанные кадры, %: ' + str(stage['frames'].get('janky_percent'))]
        lines += ['', 'Запуски приложения (мс): ' + str([x['total_time_ms'] for x in launches]),
                  '', 'Ограничения:'] + report['limitations']
        if problem:
            lines += ['', 'Проверка остановлена: ' + problem]
        self.save(self.directory, 'README.txt', '\n'.join(lines))
        archive = self.directory.with_suffix('.zip')
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as zipped:
            for path in sorted(self.directory.rglob('*')):
                if path.is_file():
                    zipped.write(path, str(Path(self.directory.name) / path.relative_to(self.directory)))
        print('Отчёт:', archive, flush=True)
        return archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['snapshot', 'measure', 'check', 'compare'], nargs='?', default='snapshot')
    parser.add_argument('reports', nargs='*', type=Path, help='Два ZIP-отчёта для compare')
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--output', type=Path, default=Path.home() / 'Desktop')
    args = parser.parse_args()
    if args.mode == 'compare':
        if len(args.reports) != 2:
            parser.error('Для compare передай старый и новый ZIP-отчёты')
        try:
            print(compare_reports(*args.reports))
        except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
            parser.error(str(error))
        return 0
    if args.reports:
        parser.error('ZIP-файлы разрешены только в режиме compare')
    adb = shutil.which('adb')
    if not adb:
        parser.error('Не найден adb')
    if not args.serial:
        found = subprocess.run([adb, 'get-serialno'], capture_output=True, text=True, timeout=20)
        if found.returncode or not found.stdout.strip() or found.stdout.strip() == 'unknown':
            parser.error('Укажи --serial или ANDROID_SERIAL; для автовыбора нужен ровно один подключённый ТВ')
        args.serial = found.stdout.strip()
    args.output.mkdir(parents=True, exist_ok=True)
    directory = args.output / ('SashaTV-report-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    directory.mkdir()
    collector = Collector(adb, args.serial, directory)
    launches, problem = [], None
    try:
        collector.connect()
        if args.mode == 'measure':
            launches = collector.measure()
        elif args.mode == 'check':
            collector.snapshot('baseline')
            steps = [
                ('after-reboot', 'Перезагрузи ТВ, дождись главного экрана Luma и нажми Enter.'),
                ('after-sleep', 'Пультом отправь ТВ в сон, разбуди, вернись в Luma и нажми Enter.'),
                ('after-playback', 'Открой видео из Lampa в VLC, вернись аппаратной Home в Luma и нажми Enter.'),
                ('hdmi-on', 'Включи PlayStation, проверь вход через «Устройства», вернись Home в Luma и нажми Enter.'),
                ('hdmi-off', 'Выключи PlayStation, оставь Luma открытым и нажми Enter.')]
            for label, prompt in steps:
                print(prompt + ' Для пропуска введи skip; для завершения — stop.', flush=True)
                answer = input().strip().lower()
                if answer == 'stop':
                    break
                if answer == 'skip':
                    continue
                collector.snapshot(label)
        else:
            collector.snapshot('snapshot')
    except (RuntimeError, OSError, KeyboardInterrupt, EOFError) as error:
        problem = str(error) or error.__class__.__name__
        print('Проверка остановлена:', problem, flush=True)
    collector.finish(collector.launches, problem)
    return 1 if problem else 0


if __name__ == '__main__':
    raise SystemExit(main())
