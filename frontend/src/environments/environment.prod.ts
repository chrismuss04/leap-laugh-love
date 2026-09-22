// Environment configuration for production
export const environment = {
  production: true,
  apiUrl: 'https://api.leaplaughlove.com',  // Update with your production domain
  apiEndpoints: {
    auth: '/api/auth',
    users: '/api/users',
    clients: '/api/iam/v1/clients',
    portfolio: '/api/portfolio',
    account: '/api/account',
    order: '/api/order',
    marketData: '/api/market-data'
  }
};
