# Smart-community-resource-allocation-system-for-emergencies-and-disaster

1. Project Overview

The Community Resource Allocation System (CRAS) is a full-stack application designed to support the management and allocation of essential community resources during emergencies and disasters.

The system helps manage emergency requests, maintain resource availability, prioritize urgent needs, and coordinate resource allocation through a centralized platform.

2. Objectives

* Manage emergency resource requests efficiently.
* Maintain records of resources, locations, users, and allocations.
* Prioritize requests according to emergency severity and other relevant factors.
* Support resource allocation when resources are limited.
* Provide role-based access for community users, resource managers, and administrators.
* Display location-related information through GIS integration.
* Maintain audit records and support real-time updates.

3. Technology Stack

Component	Technology
Frontend	React.js, Vite, CSS
Backend	Java, Spring Boot
Database	MySQL
Database Integration	Spring Data JPA
Authentication	Spring Security, JWT
Password Security	BCrypt
Real-time Communication	WebSocket
GIS	Leaflet and OpenStreetMap
Build Tool	Maven

4. Main Modules

4.1 User Authentication and Security

Provides user registration, login, authentication, and role-based access control.

4.2 Resource Management

Supports maintaining resource information, availability, and quantities.

4.3 Emergency Request Management

Allows emergency requests to be submitted and managed according to their details and status.

4.4 OS Scheduling and Resource Allocation

Uses operating-system scheduling concepts to prioritize emergency requests and support allocation of limited resources.

4.5 Database Management

Stores information about users, locations, resources, emergency requests, allocations, and audit records.

4.6 GIS and Location Management

Supports location-based information to help identify where resources and emergency requests are situated.

4.7 Real-Time Updates and Auditing

Supports real-time communication and records relevant system activities for monitoring and traceability.

4.8 AI-Based Resource Shortage Prediction — Planned

A future development module intended to estimate possible resource shortages using available historical and operational data. Prediction scores and confidence values will be integrated during a later development phase.

5. Project Structure

CRAS/
├── README.md
├── docs/
│   ├── README.md
│   ├── architecture.md
│   └── modules/
├── database/
│   └── schema.sql
├── backend/
│   ├── pom.xml
│   └── src/
│       └── main/
│           ├── java/
│           └── resources/
└── frontend/
    ├── package.json
    ├── index.html
    └── src/

Note: This represents the intended project structure. Adjust the filenames if they differ from the actual repository.

6. Team Responsibilities

Team Member	Role	Responsibilities
Arushi Rawat	Team Leader and System Integration	Project coordination, architecture, module integration, AI prediction planning, and overall testing
Vinayak	DBMS and Backend	Database schema, backend APIs, data persistence, transactions, and concurrency
Aaman	OS Scheduling and Resource Allocation	Priority scheduling, priority aging, preemption, scheduling queues, and resource allocation
Tulsi	Frontend and Testing	User interface, dashboard, frontend integration, and testing

7. Development Phases

* Phase I — Planning and Architecture: Requirements, research, system design, and module allocation.
* Phase II — Database and Backend: Database design, backend APIs, authentication, and scheduling integration.
* Phase III — Prediction and Enhancement: AI-based resource shortage prediction and further scheduling improvements.
* Phase IV — Frontend Integration: Dashboard improvements, GIS presentation, and real-time updates.
* Phase V — Testing: Functional testing, concurrency testing, and limited-resource scenarios.
* Phase VI — Final Integration: System evaluation, documentation, and demonstration.

8. Setup Instructions

Prerequisites

Install the following software before running the application:

* Java 17 or a compatible version required by the backend configuration
* Maven
* Node.js and npm
* MySQL Server

Backend

1. Create the required MySQL database.
2. Configure the database connection in backend/src/main/resources/application.properties.
3. Open a terminal in the backend directory.
4. Run:

mvn spring-boot:run

Frontend

1. Open another terminal in the frontend directory.
2. Install the dependencies:

npm install

3. Start the development server:

npm run dev

4. Open the local URL displayed in the terminal.

Important: Database credentials, JWT secrets, and other sensitive configuration values should be kept out of public commits.

9. Current Scope and Future Work

The project is being developed incrementally. The implemented features should be verified against the current source code before being described as complete.

Future enhancements may include AI-based shortage prediction, prediction confidence scores, improved GIS visualization, and additional performance and concurrency testing.

10. Project Purpose

CRAS aims to demonstrate how database management, operating-system scheduling, web development, security, and location-based services can work together to support more organized community resource allocation during emergencies.
