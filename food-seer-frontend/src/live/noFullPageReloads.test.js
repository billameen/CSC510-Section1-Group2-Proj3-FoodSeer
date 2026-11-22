// Acceptance criterion (M6): pages update in place, so nothing in src/ may
// force a full page reload any more.
const fs = require('fs');
const path = require('path');

const sourceFiles = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(full);
    return /\.jsx?$/.test(entry.name) && !/\.test\.jsx?$/.test(entry.name) ? [full] : [];
  });

test('no source file calls window.location.reload', () => {
  const offenders = sourceFiles(path.join(__dirname, '..')).filter((file) =>
    /location\.reload\s*\(/.test(fs.readFileSync(file, 'utf8'))
  );

  expect(offenders).toEqual([]);
});
