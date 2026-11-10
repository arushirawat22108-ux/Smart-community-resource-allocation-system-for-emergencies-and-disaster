CRAS – Community Resource Allocation System

AI-Driven Emergency Resource Allocation and Management System

1. About the Project

CRAS (Community Resource Allocation System) is an intelligent resource management system designed to improve the distribution of essential resources during emergencies and natural disasters.

During emergencies such as floods, earthquakes and landslides, resources like food, water, medical kits, ambulances and shelters become limited. CRAS helps manage these resources by prioritising requests, monitoring availability and allocating resources efficiently.

The system combines Operating System scheduling algorithms, Database Management Systems and Artificial Intelligence to support efficient, reliable and transparent resource allocation.

2. Objectives

* Efficiently allocate limited resources during emergencies.
* Prioritise requests based on emergency severity and resource requirements.
* Prevent duplicate allocations and resource conflicts.
* Monitor resource inventory and availability.
* Use AI/ML to predict potential shortages and identify unusual requests.
* Maintain secure and consistent database operations.
* Provide real-time updates through the application.

3. Technology Stack

Component	Technologies
Frontend	HTML5, CSS3, JavaScript
Backend	Java 17, Spring Boot
Backend Framework	Spring Data JPA
Database	MySQL 8.0
AI/ML	Python, Scikit-learn
Machine Learning	Random Forest, XGBoost (where supported by the installed environment)
Security	Spring Security, JWT, BCrypt
Communication	REST APIs, Server-Sent Events (SSE)
Build Tool	Maven

Note: The current frontend uses Vanilla JavaScript, not React.js.

4. Key Features

Resource Management

* Resource inventory management.
* Emergency request management.
* Resource availability tracking.
* Allocation and stock management.
* Prevention of negative inventory.

Operating System Concepts

* First Come First Serve (FCFS).
* Shortest Job First (SJF).
* Shortest Remaining Time First (SRTF).
* Priority Scheduling.
* Round Robin Scheduling.
* Priority aging and pre-emption.
* Banker’s Algorithm and safe-state checking.

Database Management

* MySQL database integration.
* Spring Data JPA.
* Database transactions.
* Concurrency control.
* Locking and versioning.
* Stored procedures and views.
* Audit logging and idempotency handling.

Artificial Intelligence and Machine Learning

* AI-based shortage prediction.
* Random Forest-based prediction model.
* Model comparison and evaluation.
* Prediction confidence scores.
* Risk classification.
* Anomaly detection.
* Rule-based fallback when AI predictions are unavailable or unreliable.

Security

* JWT-based authentication.
* BCrypt password hashing.
* Role-based access control.
* Spring Security integration.
* Login attempt protection.

Real-Time Updates

* Server-Sent Events (SSE).
* Live application updates.
* Request and allocation monitoring.

5. System Architecture

The system follows a layered architecture:

1. Frontend Layer: HTML, CSS and JavaScript provide the user interface and application interactions.
2. Backend Layer: Java and Spring Boot handle business logic, REST APIs, request processing and resource allocation.
3. Database Layer: MySQL stores users, requests, inventory, allocation records and other application data.
4. AI/ML Layer: Python processes data and provides shortage-risk predictions and anomaly detection.
5. Security Layer: Spring Security and JWT manage authentication and access control.

6. Project Structure

CRAS_Phase3_Final/
│
├── ai/
│   ├── model/
│   ├── reports/
│   ├── train_model.py
│   ├── anomaly_detection.py
│   └── serve.py
│
├── backend/
│   ├── src/main/java/com/cras/
│   │   ├── config/
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── entity/
│   │   ├── repository/
│   │   ├── service/
│   │   └── os/
│   │
│   └── pom.xml
│
├── data/
│
├── db/
│
├── docs/
│
├── frontend/
│   ├── index.html
│   ├── css/
│   │   └── styles.css
│   └── js/
│       ├── main.js
│       └── core.js
│
├── os_sim/
│
├── verification/
│
├── requirements.txt
├── start_backend.bat
├── start_frontend.bat
├── start_ai.bat
└── start_demo.sh

7. Installation and Execution

Prerequisites

* Java JDK 17 or later
* Maven
* MySQL 8.0
* Python 3.10 or later
* Modern web browser

Step 1: Clone the Repository

git clone YOUR_GITHUB_REPOSITORY_URL
cd CRAS_Phase3_Final

Step 2: Configure MySQL

Ensure that MySQL is installed and running.

The backend uses the cras_db database.

Configure your database credentials using environment variables:

Windows PowerShell:

$env:DB_USER="root"
$env:DB_PASSWORD="your_mysql_password"

Step 3: Start the Backend

cd backend
mvn spring-boot:run

Backend runs at:

http://localhost:8080

Step 4: Start the Frontend

Open another terminal:

cd frontend
python -m http.server 5500

Open the application at:

http://localhost:5500

Step 5: Start the AI Service

From the project root, create and activate a Python virtual environment.

python -m venv .venv

Activate it and install dependencies:

pip install -r requirements.txt

Start the AI service:

python ai/serve.py --port 8001

AI service health endpoint:

http://localhost:8001/health

8. Testing and Verification

The project includes verification scripts for different components.

Run the available checks from the project root:

bash verification/run_all_checks.sh

Additional Python checks:

python verification/test_python.py
python verification/sql_logic_check.py

9. Project Development Phases

* Phase I: Project proposal, research and system architecture.
* Phase II: Database design, backend development and resource management.
* Phase III: Scheduling algorithms, AI integration, security, frontend integration and system verification.

10. Future Scope

* Integration with real-time disaster management datasets.
* GIS-based resource tracking and mapping.
* Improved AI prediction using historical disaster data.
* Integration with emergency services and NGOs.
* Mobile application development.
* Cloud deployment and scalability improvements.

11. Team

Project: Community Resource Allocation System (CRAS)

Developed as an academic project to demonstrate the practical application of Operating Systems, Database Management Systems, Artificial Intelligence and Full-Stack Development.

⸻

12. Disclaimer

CRAS is an academic prototype. Predictions and resource allocation decisions should be validated using reliable real-world data and domain expertise before deployment in actual emergency situations.

Made with dedication by Team CRAS.h