# LeapLaugh Frontend - Angular Application

Professional sign-in page for the Leap Laugh Love portfolio management platform.

## 🚀 Quick Start

### Prerequisites
- Node.js v18+ ([Download](https://nodejs.org/))
- npm (comes with Node.js)

### Installation

```bash
# Navigate to frontend directory
cd frontend

# Install dependencies
npm install

# Start development server
npm start
```

Visit `http://localhost:4200` in your browser.

## 📁 Project Structure

```
frontend/
├── src/
│   ├── app/
│   │   ├── sign-in/                 # Sign-in page component
│   │   ├── services/                # Business logic services
│   │   │   └── auth.service.ts      # Authentication service
│   │   ├── interceptors/            # HTTP interceptors
│   │   │   └── auth.interceptor.ts  # Add token to requests
│   │   ├── app.module.ts            # App module configuration
│   │   └── app.component.*          # Root component
│   ├── main.ts                      # Bootstrap file
│   └── index.html                   # Main HTML
├── angular.json                     # Angular CLI config
├── package.json                     # Dependencies
└── tsconfig.json                    # TypeScript config
```

## 🎨 Design Features

- **Dark Theme**: Professional navy blue background (#0a0e27)
- **Teal Accents**: Modern teal color (#1dd1a1) for interactive elements
- **Split Layout**: Branding on left, login form on right
- **Responsive**: Works on desktop, tablet, and mobile
- **Testimonial**: Customer quote with avatar
- **Form Validation**: Real-time email and password validation
- **Loading State**: Animated spinner during login
- **Error Handling**: Clear error messages

## ⚙️ Configuration

### Backend API URL

Update the API endpoint in `src/app/services/auth.service.ts`:

```typescript
private apiUrl = 'http://localhost:8080/api/auth';  // Change this
```

### CORS Settings

Ensure your backend allows requests from `http://localhost:4200`:

```java
// Spring Boot example
@Configuration
public class CorsConfig {
  @Bean
  public WebMvcConfigurer corsConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
          .allowedOrigins("http://localhost:4200")
          .allowedMethods("*")
          .allowCredentials(true);
      }
    };
  }
}
```

## 🔑 Features

✅ Email & password validation  
✅ Password visibility toggle  
✅ Remember me checkbox  
✅ Loading indicators  
✅ Error message display  
✅ JWT token management  
✅ HTTP interceptor for auth  
✅ Responsive design  
✅ Accessible form labels  

## 📝 API Endpoints

### Login
```
POST /api/auth/login
Content-Type: application/json

Request:
{
  "email": "user@example.com",
  "password": "password123"
}

Response (200 OK):
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "user": {
    "id": "user-123",
    "email": "user@example.com",
    "name": "User Name"
  }
}
```

## 🛠️ Build Commands

```bash
# Development (with hot reload)
npm start

# Production build
npm run build

# Watch for changes during development
npm run watch

# Run tests
npm test
```

## 📚 Learn More

See [FRONTEND_GUIDE.md](./FRONTEND_GUIDE.md) for:
- Complete Angular explanation
- Detailed code breakdown
- How all components work together
- Integration with backend
- Security features
- Troubleshooting

## 🔐 Security

- Passwords never logged or displayed in console
- JWT tokens stored securely in localStorage
- Authorization header automatically added to API calls
- 401 errors trigger re-authentication
- Form validation prevents invalid submissions

## 🐛 Troubleshooting

**Port 4200 already in use?**
```bash
ng serve --port 4300
```

**Module not found?**
```bash
npm install
npm start
```

**Backend not responding?**
- Check backend is running on correct URL
- Check CORS is configured
- Open browser DevTools (F12) → Network tab
- Look for failed requests and error messages

**Form not validating?**
- Open browser console (F12)
- Check `signinForm.errors` to see validation errors
- Verify form field values match expected format

## 📧 Support

For issues with Angular setup, visit:
- [Angular Docs](https://angular.io/docs)
- [Stack Overflow - Angular tag](https://stackoverflow.com/questions/tagged/angular)

## 📄 License

This project is part of Leap Laugh Love portfolio management platform.
