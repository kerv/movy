const assert = require('node:assert/strict');
const fs = require('node:fs');
const read = path => fs.readFileSync(path, 'utf8');
const deploy = read('.github/workflows/deploy.yml');
const validate = read('.github/workflows/validate.yml');
for (const [name, workflow] of [['release', deploy], ['PR validation', validate]]) {
  assert.ok(!workflow.includes('ubuntu-latest'), `${name} must not silently move browser tests to an unsupported Ubuntu release`);
  assert.match(workflow, /runs-on: ubuntu-24\.04/);
  assert.match(workflow, /runs-on: windows-2025/);
  assert.match(workflow, /global-json-file: global\.json/);
  assert.ok(!workflow.includes('dotnet-version:'), `${name} SDK version belongs in global.json only`);
}
const sdk = JSON.parse(read('global.json')).sdk;
assert.match(sdk.version, /^10\.0\.\d+$/);
assert.equal(sdk.rollForward, 'disable');
assert.equal(sdk.allowPrerelease, false);
const project = read('desktop/Movy.Desktop/Movy.Desktop.csproj');
assert.ok(!/microsoft\.(?:netcore|windowsdesktop)\.app\.runtime\.win-x64\/\d/i.test(project), 'runtime notice paths must not hardcode a runtime version');
assert.match(project, /@\(ResolvedRuntimePack\)/);
assert.match(project, /%\(PackageDirectory\)/);
assert.match(project, /DependsOnTargets="ResolveFrameworkReferences"/);
assert.match(project, /BeforeTargets="AssignTargetPaths"/);
assert.match(validate, /Windows publish and license packaging check/);
for (const name of ['DotNET-Runtime-LICENSE.txt', 'WPF-Runtime-LICENSE.txt', 'WebView2-LICENSE.txt', 'WebView2-NOTICE.txt']) {
  assert.ok(read('desktop/build.ps1').includes(name), `${name} must be verified before ZIP publication`);
}
const release = JSON.parse(read('release/version.json'));
const android = read('app/build.gradle');
assert.match(android, new RegExp(`versionCode ${release.versionCode}\\b`));
assert.ok(android.includes(`versionName "${release.versionName}"`));
assert.ok(read('desktop/Directory.Build.props').includes(`<Version>${release.versionName}.0</Version>`));
console.log('PASS pinned CI runners/SDK, resolved runtime notice paths, PR Windows packaging, and aligned release versions');
