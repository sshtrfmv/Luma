import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('publication', Path(__file__).parents[1] / 'check-publication.py')
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


class Publication(unittest.TestCase):
    def test_signing_key_path_is_rejected(self):
        self.assertIn('private/generated file', publication.inspect('app/launcher.p12', b'key'))

    def test_fake_credential_is_detected_without_displaying_it(self):
        self.assertIn('GitHub token', publication.inspect('settings.txt', ('ghp_' + 'x' * 40).encode()))

    def test_runtime_token_variable_is_allowed(self):
        self.assertEqual([], publication.inspect('Source.java', b'String token = decrypt(prefs.getString("secret", ""));'))

    def test_unverified_artwork_is_rejected(self):
        self.assertIn('obsolete unverified artwork', publication.inspect('reference_screen.png', b'png'))

    def test_unknown_jar_is_rejected(self):
        self.assertIn('unrecognized binary JAR', publication.inspect('unknown.jar', b'jar'))

    def test_vector_asset_is_allowed(self):
        self.assertEqual([], publication.inspect('app/src/main/res/drawable/luma_icon.xml', b'<vector/>'))


if __name__ == '__main__':
    unittest.main()
