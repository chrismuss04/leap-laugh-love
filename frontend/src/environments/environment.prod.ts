// Environment configuration for production
export const environment = {
  production: true,
  apiUrl: 'https://api.leaplaughlove.com',  // Update with your production domain
  apiEndpoints: {
    auth: '/api/auth',
    users: '/api/users',
    portfolio: '/api/portfolio',
    trading: '/api/trading',
    marketData: '/api/market-data'
  }
};
