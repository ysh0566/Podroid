#!/usr/bin/env python3
"""Generate safe workflow outputs without evaluating branch/tag names as shell code."""
import os
import re
from pathlib import Path


def metadata(env):
    properties = dict(line.split('=', 1) for line in Path('gradle.properties').read_text().splitlines()
                      if line.startswith('podroid') and '=' in line)
    source = Path('app/build.gradle.kts').read_text()
    base_code = int(re.search(r'\bversionCode\s*=\s*(\d+)', source)[1])
    base_name = re.search(r'\bversionName\s*=\s*"([^"]+)"', source)[1]
    run = int(env['GITHUB_RUN_NUMBER'])
    code = 100000 + run
    if not base_code < code <= 2100000000:
        raise ValueError('CI versionCode must increase and remain within Android limits')
    # Tags need not be semantic versions; use them only after removing characters
    # unsuitable for APK metadata, never in a run script or output filename.
    tag = re.sub(r'[^A-Za-z0-9._+-]', '-', env.get('GITHUB_REF_NAME', ''))[:80]
    version = tag.removeprefix('v') if env.get('GITHUB_REF_TYPE') == 'tag' else f'{base_name}-ci.{run}'
    return dict(kernel=properties['podroidKernelVersion'], qemu=properties['podroidQemuVersion'],
                uml=properties['podroidUmlKernelCommit'], system_version=str(base_code),
                version_code=str(code), version_name=version,
                artifact=f'podroid-{code}-{env["GITHUB_SHA"][:12]}')


if __name__ == '__main__':
    values = metadata(os.environ)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in values.items():
            if '\n' in value or '\r' in value:
                raise ValueError('Invalid multiline workflow output')
            output.write(f'{key}={value}\n')
