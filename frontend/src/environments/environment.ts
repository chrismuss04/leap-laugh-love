// Environment configuration for development
// This file is NOT committed to version control

export const environment = {
  production: false,
  // Empty so requests stay relative (e.g. /api/iam/v1/clients/register) and get
  // picked up by the ng serve dev-server proxy (proxy.conf.js), which forwards
  // them to the real backend ports instead of the browser hitting them directly.
  apiUrl: '',
  apiEndpoints: {
    auth: '/api/auth',
    users: '/api/users',
    clients: '/api/iam/v1/clients',
    portfolio: '/api/portfolio',
    trading: '/api/trading',
    marketData: '/api/market-data'
  }
};
