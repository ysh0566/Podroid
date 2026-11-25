import unittest
from metadata import metadata


class MetadataTest(unittest.TestCase):
    def setUp(self):
        self.env = dict(GITHUB_RUN_NUMBER='1', GITHUB_SHA='a' * 40,
                        GITHUB_REF_TYPE='branch', GITHUB_REF_NAME='main')

    def test_ci_counter_does_not_advance_guest_migration_version(self):
        first = metadata(self.env)
        second = metadata(dict(self.env, GITHUB_RUN_NUMBER='2'))
        self.assertEqual(int(first['version_code']) + 1, int(second['version_code']))
        self.assertEqual(first['system_version'], second['system_version'])
        self.assertLess(int(first['system_version']), int(first['version_code']))

    def test_tags_cannot_inject_script_or_output_lines(self):
        value = metadata(dict(self.env, GITHUB_REF_TYPE='tag', GITHUB_REF_NAME='v1.3.1$(echo-x)\ny=bad'))
        self.assertRegex(value['version_name'], r'^[A-Za-z0-9._+-]+$')
        self.assertNotIn('echo', value['artifact'])

    def test_semantic_tag_supplies_version_name(self):
        value = metadata(dict(self.env, GITHUB_REF_TYPE='tag', GITHUB_REF_NAME='v1.3.1'))
        self.assertEqual('1.3.1', value['version_name'])

    def test_android_version_overflow_fails(self):
        with self.assertRaises(ValueError):
            metadata(dict(self.env, GITHUB_RUN_NUMBER='2100000000'))
