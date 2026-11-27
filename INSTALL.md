# FoodSeer Frontend Recreation - Setup Guide

This guide will help you set up and run the FoodSeer application.

## 📑 Table of Contents

- [Prerequisites](#prerequisites)
- [Dependencies](#dependencies)
- [Backend Setup](#backend-setup)
  - [1. Configure MySQL Database](#1-configure-mysql-database)
  - [2. Create and update applicationproperties file](#2-create-and-update-applicationproperties-file)
  - [3. How to Set Up Ollama](#3-how-to-set-up-ollama)
  - [4. Build and Run Backend](#4-build-and-run-backend)
  - [5. Verify Backend](#5-verify-backend)
- [Frontend Setup](#frontend-setup)
- [Notes](#notes)
- [Using the Application](#using-the-application)
- [API Endpoints](#api-endpoints)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
  - [Common Issues](#common-issues)
  - [Chatbot-Specific Issues](#chatbot-specific-issues)
- [Getting Help](#getting-help)
- [Quick Start Summary](#quick-start-summary)


## Prerequisites

- [Java JDK](https://www.oracle.com/java/technologies/downloads/) (version 11 or higher, Java 21 recommended)
- [Maven](https://maven.apache.org/download.cgi) (version 3.6 or higher)
- [Node.js](https://nodejs.org/) (version 16.x or higher)
- [npm](https://www.npmjs.com/) (version 8.x or higher)
- [MySQL](https://dev.mysql.com/downloads/workbench/) (version 8.0 or higher)
- Git
- A modern web browser (Chrome, Firefox, Safari, or Edge)
- A chromium based web browser (Google Chrome)

## Dependencies 

Every library, its version, and its license are listed in [THIRD_PARTY_LIBRARIES.md](THIRD_PARTY_LIBRARIES.md).

| Language | Tool         | Command                  |
| -------- | ------------ | ------------------------ |
| Java     | Maven        | `mvn clean install`      |
| Node     | npm          | `npm install`            |
| Tests    | Maven + Jest | `mvn test` / `npm test`  |
| Coverage | JaCoCo       | `mvn test jacoco:report` |

## Frontend Setup

### 1. Install Dependencies

```bash
cd food-seer-frontend
npm install
```

### 2. Run Frontend

```bash
npm start
```

The frontend will start on `http://localhost:3000`

## Notes

- The frontend uses JWT tokens stored in localStorage for authentication
- Tokens expire after 7 days
- All API calls include the Bearer token in the Authorization header
- Protected routes redirect to login if not authenticated

## Backend Setup

### 1. Configure MySQL Database

Make sure MySQL is running on your machine. The application will automatically create the database if it doesn't exist.

Default configuration:
- Database: `users`
- Host: `localhost:3306`
- Username: `root`
- Password: `` (empty)

If your MySQL setup is different, update `food-seer-backend/src/main/resources/application.properties`

### 2. Create and update application.properties file

At the following path you will find a template file: food-seer-backend/src/main/resources/application.properties.template

Use this template and make a copy in the same folder called application.properties

Inside of this newly created application.properties file change the following
- spring.datasource.password: To whatever you have your MySQL password set to
- app.jwt-secret: Using https://emn178.github.io/online-tools/sha256.html make a jwt secret

The default admin password is not in this file. Set it as an environment
variable before the first start, and the `admin` account is created with it:

```bash
# macOS / Linux
export FOODSEER_ADMIN_PASSWORD='choose-a-password'
# Windows Command Prompt
set FOODSEER_ADMIN_PASSWORD=choose-a-password
# Windows PowerShell
$env:FOODSEER_ADMIN_PASSWORD = 'choose-a-password'
```

It is only used when no `admin` account exists yet, and it is never printed in
the logs. If an admin already exists, its password is left alone.

### 3. How to Set Up Ollama

#### Step 1: Download Ollama

##### For Windows:
1. Go to https://ollama.com/download
2. Click "Download for Windows"
3. Run the installer (`OllamaSetup.exe`)
4. Follow the installation wizard

##### For Mac:
1. Go to https://ollama.com/download
2. Click "Download for macOS"
3. Open the `.dmg` file and drag Ollama to Applications

##### For Linux:
```bash
curl -fsSL https://ollama.com/install.sh | sh
```

#### Step 2: Pull the qwen2.5:1.5b Model

After installation, open a terminal and run:

```bash
ollama pull qwen2.5:1.5b
```

This will download the model (~1GB). Wait for it to complete. This is the
model the backend asks for by default; if you pull a different one, set
`foodseer.ollama.model` to match (see Step 4).

#### Step 3: Start Ollama Server

Ollama should start automatically after installation. To verify it's running:

```bash
ollama list
```

You should see `qwen2.5:1.5b` in the list.

If Ollama isn't running, start it:
```bash
ollama serve
```

The server will run on `http://localhost:11434` by default.

#### Step 4 (optional): Point the backend at a different Ollama or model

The defaults work with the steps above, so most people can skip this. To
change them, add any of these to `application.properties`, or set the
environment variable instead:

| Property | Environment variable | Default | What it does |
| :- | :- | :- | :- |
| `foodseer.ollama.url` | `FOODSEER_OLLAMA_URL` | `http://localhost:11434` | Where Ollama listens |
| `foodseer.ollama.model` | `FOODSEER_OLLAMA_MODEL` | `qwen2.5:1.5b` | Which pulled model to use |
| `foodseer.ollama.timeout-ms` | `FOODSEER_OLLAMA_TIMEOUTMS` | `60000` | Longest wait for an answer, in milliseconds |

If Ollama is down, too slow, or missing the model, `POST /api/chat` answers
503 and the chat page says the assistant is unavailable. The rest of the app
keeps working.


### 4. Build and Run Backend

```bash
cd food-seer-backend
mvn clean install
mvn spring-boot:run
```

The backend will start on `http://localhost:8080`

### 5. Verify Backend

On first start, if `FOODSEER_ADMIN_PASSWORD` is set, the application creates an admin user:
- Username: `admin`
- Password: the value of `FOODSEER_ADMIN_PASSWORD`

Staff and admin accounts can't be created from the public sign-up form. Log in
as `admin` and create them on the User Management page (or `POST /api/users`).

You can test the login endpoint:
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

## Using the Application

### Application Flow:

1. **Login Page** (`/`)
   - Log in as `admin` with your `FOODSEER_ADMIN_PASSWORD`
   - Or register a new account
   - Clean, modern login interface

2. **Preferences Page** (`/preferences`)
   - After login, set your preferences
   - **Step 1**: Select budget (Under $10, Under $20, Under $30, or custom)
   - **Step 2**: Select dietary restrictions (Vegan, Vegetarian, Lactose intolerant, or custom)
   - Click "Next" between steps
   - Preferences saved to backend on completion

3. **Recommendations Page** (`/recommendations`)
   - View personalized recommendations
   - See saved budget and dietary restrictions
   - Update preferences anytime
   - Logout when done

4. **AI Assistant** (`/chatbot`) - **Customer Only**
   - Click "🤖 AI Assistant" in navigation
   - Answer 3 questions:
     - **Q1:** How are you feeling today? (e.g., "tired")
     - **Q2:** How hungry are you? (e.g., "very hungry")
     - **Q3:** What food are you in the mood for? (e.g., "comfort food")
   - AI analyzes responses with your preferences
   - Get ONE personalized food recommendation
   - Click "Order This Now!" to create order immediately
   - Click "Get Another Suggestion" to restart

---

## API Endpoints

### Authentication
- `POST /auth/login` - Login with username and password
- `POST /auth/register` - Register a new user

### User Management
- `GET /api/users/me` - Get current user info (requires authentication)
- `PUT /api/users/me/preferences` - Update user preferences (requires authentication)

### Chat/AI
- `POST /api/chat` - Send message to AI chatbot (requires authentication)

### Request/Response Examples

**Login Request:**
```json
{
  "username": "admin",
  "password": "admin123"
}
```

**Login Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer"
}
```

**Update Preferences Request:**
```json
{
  "costPreference": "under-20",
  "dietaryRestrictions": "vegan, lactose-intolerant"
}
```

**Chat Request** (`history` is optional: earlier turns, oldest first; only the last few are used):
```json
{
  "message": "anything cheaper?",
  "history": [
    { "role": "user", "content": "I'm tired and want comfort food" },
    { "role": "assistant", "content": "The chicken noodle soup would be cozy." }
  ]
}
```

**Chat Response** (`foods` are already checked against the menu, stock and the customer's saved allergies; `notice` is set only when a suggestion was left out):
```json
{
  "message": "The vegetable soup is cheaper and just as warm.",
  "foods": [
    { "id": 15, "foodName": "VEGETABLE SOUP", "price": 6, "calories": 180, "allergies": ["SOY"] }
  ],
  "notice": null
}
```

If the model is down, too slow, or not installed, the response is `503` with
`{"message": "..."}` instead.

---

## Testing

### Backend Tests

Using Maven:
```bash
cd food-seer-backend
mvn test

# Run specific test class
mvn test -Dtest=YourTestClass

# Run with coverage
mvn test jacoco:report
```

### Frontend Tests

```bash
cd food-seer-frontend
npm test
```

### Testing the Chatbot Integration

1. Start Ollama, backend, and frontend
2. Login as a **customer** (not admin/staff)
3. Navigate to "🤖 AI Assistant"
4. Complete the 3-question conversation
5. Verify recommendation is received
6. Test "Order This Now!" functionality
7. Test "Get Another Suggestion"

### Code Coverage

**Backend (Maven with JaCoCo):**
```bash
cd food-seer-backend
mvn clean test jacoco:report
# View report at: target/site/jacoco/index.html
```

---

## Troubleshooting

### Common Issues

#### Port Already in Use

**Problem:** Port 8080 (backend) or 3000 (frontend) already in use

```bash
# Find and kill process using port
# On macOS/Linux:
lsof -i :8080
lsof -i :3000
# Kill process:
lsof -ti:8080 | xargs kill -9

# On Windows:
netstat -ano | findstr :8080
# Kill using task manager or:
taskkill /PID <PID> /F
```

#### Java Version Issues

**Problem:** Wrong Java version
```bash
# Check current Java version
java -version

# Set JAVA_HOME environment variable
# On macOS/Linux:
export JAVA_HOME=/path/to/java11
# On Windows:
set JAVA_HOME=C:\Program Files\Java\jdk-11

# Verify
echo $JAVA_HOME  # macOS/Linux
echo %JAVA_HOME%  # Windows
```

#### MySQL Connection Issues

**Problem:** Cannot connect to database

Solutions:
- Verify MySQL service is running:
  ```bash
  # On macOS:
  brew services start mysql
  # On Linux:
  sudo systemctl start mysql
  # On Windows: Start MySQL service from Services panel
  ```
- Check database credentials in `application.properties`
- Ensure database exists and user has proper permissions
- Verify MySQL is listening on port 3306

#### Maven Build Failures

**Problem:** Maven dependencies not downloading
```bash
# Clear Maven cache and rebuild
mvn clean install -U

# Force update snapshots
mvn clean install -U -DskipTests

# Clear local repository cache
rm -rf ~/.m2/repository
mvn clean install
```

#### Docker Issues

**Problem:** Docker build fails
```bash
# Clear Docker cache and rebuild
docker-compose down -v
docker-compose build --no-cache
docker-compose up
```

**Problem:** Permission denied errors
```bash
# On Linux, add user to docker group
sudo usermod -aG docker $USER
# Log out and back in for changes to take effect
```

#### Spring Boot Application Won't Start

**Problem:** Application fails to start
```bash
# Check logs for specific errors
mvn spring-boot:run

# Run with debug enabled
mvn spring-boot:run -Dspring-boot.run.arguments=--debug

# Verify all required dependencies
mvn dependency:tree
```

#### Frontend Module Not Found Errors

```bash
# Clear node_modules and reinstall
rm -rf node_modules package-lock.json
npm install

# Clear npm cache
npm cache clean --force
npm install
```

#### CORS Errors

**Problem:** Frontend can't connect to backend

Solutions:
- Verify backend is running on port 8080
- Check `@CrossOrigin` annotation in controllers
- Verify `REACT_APP_API_URL` in frontend `.env`
- Check browser console for specific CORS error

#### Login Fails

**Problem:** Cannot login with admin credentials

Solutions:
- Verify backend is running
- Check that admin user was created (check logs)
- Verify API_BASE_URL in `src/services/api.js`
- Check browser console and network tab
- Test login endpoint directly:
  ```bash
  curl -X POST http://localhost:8080/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin123"}'
  ```

### Chatbot-Specific Issues

#### "The assistant is unavailable right now" (503 from `/api/chat`)
- **Cause:** Ollama is not running, or it is on a different port or host
- **Fix:** Run `ollama serve` in a terminal, or set `foodseer.ollama.url`
- **Verify:** `curl http://localhost:11434` should answer "Ollama is running"

#### 503 saying the model "is not installed on the server"
- **Cause:** The model in `foodseer.ollama.model` has not been pulled
- **Fix:** Run `ollama pull qwen2.5:1.5b` (or the model you configured)

#### 503 after a long wait
- **Cause:** The model took longer than `foodseer.ollama.timeout-ms` to answer.
  The first question after Ollama starts is the slowest, because the model
  has to load.
- **Fix:** Ask again, or raise `foodseer.ollama.timeout-ms`

#### AI Assistant Link Not Visible
- **Cause:** Not logged in as customer
- **Fix:** Ensure you're logged in with customer role, not admin/staff

---

## Getting Help

For questions or issues:
1. Check the [GitHub Issues](https://github.com/NovaCorz/CSC510/issues)
2. Check the demo video linked in the README
3. Review contribution guidelines before submitting PRs

---

## Quick Start Summary

```bash
# 1. Clone repo
git clone https://github.com/NovaCorz/CSC510.git
cd CSC510

# 2. Setup MySQL database

# 3. Install and start Ollama
ollama pull qwen2.5:1.5b
ollama serve

# 4. Start backend
cd food-seer-backend
mvn spring-boot:run

# 5. Start frontend (in new terminal)
cd food-seer-frontend
npm install
npm start

# 6. Access application
# http://localhost:3000
# Login: admin / admin123
```









