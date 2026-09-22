// Routes the browser's /api/** calls through the dev server to the backend services, so the
// only port that ever has to be reachable from the browser is 4200. Without this the app
// called http://localhost:8081 and http://localhost:8082 directly, which only works when the
// browser runs on the same machine as the containers - it breaks over SSH port forwarding,
// a remote EC2 host, or any setup where only 4200 is published to the developer.
//
// Targets default to the published host ports for a bare "ng serve" on the developer's
// machine, and docker-compose overrides them with the compose service names.
const target = (envVar, fallback) => ({
  target: process.env[envVar] || fallback,
  changeOrigin: true,
  secure: false
});

module.exports = {
  '/api/iam': target('IAM_PROXY_TARGET', 'http://localhost:8081'),
  '/api/account': target('ACCOUNT_PROXY_TARGET', 'http://localhost:8082'),
  '/api/order': target('ORDER_PROXY_TARGET', 'http://localhost:8084'),
  '/api/marketdata': target('MARKETDATA_PROXY_TARGET', 'http://localhost:8083')
};
