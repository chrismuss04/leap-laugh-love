// Environment configuration for development
// This file is NOT committed to version control

export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080',
  apiEndpoints: {
    auth: '/api/auth',
    users: '/api/users',
    clients: '/api/iam/v1/clients',
    portfolio: '/api/portfolio',
    trading: '/api/trading',
    marketData: '/api/market-data'
  }
};
