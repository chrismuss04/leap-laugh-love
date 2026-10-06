// Run Angular specs locally or in CI and generate downloadable coverage reports.
module.exports = function (config) {
  config.set({
    frameworks: ['jasmine'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-coverage')
    ],
    reporters: ['progress', 'coverage'],
    browsers: [process.env.CI ? 'ChromeHeadlessCI' : 'ChromeHeadless'],
    customLaunchers: {
      ChromeHeadlessCI: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-dev-shm-usage']
      }
    },
    coverageReporter: {
      dir: require('path').join(__dirname, 'coverage'),
      subdir: '.',
      // lcov paths are written relative to the repo root, where SonarQube resolves them.
      reporters: [
        { type: 'html' },
        { type: 'lcovonly', projectRoot: require('path').join(__dirname, '..') },
        { type: 'text-summary' }
      ]
    },
    singleRun: true
  });
};
