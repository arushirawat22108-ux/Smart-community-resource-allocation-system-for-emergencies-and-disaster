# CRAS – Smart Community Resource Allocation System During Emergencies and Disasters

**Team ID:** T006  
**Project Domain:** Operating Systems, Database Management Systems and Artificial Intelligence  
**Project Type:** Software-Based Emergency Resource Allocation System

---

## 1. About the Project

CRAS (Community Resource Allocation System) is a smart resource management platform designed to improve the allocation of limited resources during emergencies and natural disasters such as floods, earthquakes and other critical situations.

During disasters, multiple resource requests may arrive simultaneously from hospitals, villages and households. However, the availability of essential resources such as water, food, blankets, shelters and ambulances is often limited.

Traditional resource allocation methods may depend on fixed hierarchies or manual decision-making. This can result in delays, inefficient distribution and unfair resource allocation.

**Our solution is to use dynamic priority-based scheduling combined with AI-based shortage prediction and reliable database management.**

Instead of following a fixed hierarchy, CRAS calculates the priority of each request based on its urgency and the available information.

## 2. Objectives

- Provide efficient resource allocation during emergencies and disasters.
- Replace fixed resource allocation hierarchies with dynamic priority scheduling.
- Prioritise requests based on severity, affected population, location risk and resource availability.
- Predict possible resource shortages in the next 12 hours using machine learning.
- Prevent starvation of low-priority requests through aging.
- Maintain database consistency during simultaneous resource allocation.
- Provide a platform where resource managers can manage inventory and handle incoming requests.

## 3. Key Features

### A. Dynamic Priority-Based Resource Allocation

CRAS calculates a priority score for incoming requests using multiple factors:

- Number of people affected.
- Emergency severity.
- Location risk.
- Resource availability and scarcity.
- Waiting time.
- AI-based shortage prediction signal, where applicable.

The system uses these factors to determine which requests should receive attention first.

This removes the dependency on a permanent hierarchy such as hospital > village > household.

### B. Operating System Scheduling Algorithms

The project incorporates important Operating System scheduling concepts:

| Algorithm / Concept | Purpose |
|---|---|
| First Come First Serve (FCFS) | Processes requests in arrival order. |
| Shortest Job First (SJF) | Selects the request with the shortest estimated processing time. |
| Shortest Remaining Time First (SRTF) | Preemptive version of SJF. |
| Priority Scheduling | Selects requests based on priority. |
| Round Robin | Gives processes a fixed time quantum. |
| Aging | Gradually increases the priority of waiting requests. |
| Preemption | Allows critical work to take precedence over lower-priority work. |
| Backfilling | Uses available resources for suitable waiting requests without unnecessarily delaying important work. |

### C. Deadlock Avoidance

The project incorporates **Banker's Algorithm** to check whether resource allocation leaves the system in a safe state.

It uses the concept of safe sequences to help avoid unsafe resource allocation situations.

### D. AI-Based Resource Shortage Prediction

CRAS includes an AI module that estimates the probability of a resource shortage in the next 12 hours.

The ensemble approach uses three machine learning models:

1. **Logistic Regression:** Estimates shortage probability using input features and provides a statistical classification baseline.
2. **Random Forest:** Combines multiple decision trees to identify complex relationships in the data.
3. **Gradient Boosting:** Builds decision trees sequentially, with each new tree attempting to correct previous prediction errors.

The predictions are combined to estimate the shortage probability.

The AI module also calculates a confidence score to indicate the estimated reliability of its prediction.

The AI output acts as an additional signal in the dynamic priority calculation rather than independently allocating resources.

### E. Database Management and Concurrency Control

CRAS uses MySQL to maintain information about users, resource requests, inventory and allocation records.

Important DBMS concepts include:

- **ACID Properties:** Atomicity, Consistency, Isolation and Durability.
- **Pessimistic Locking:** Prevents conflicting updates by locking records during transactions.
- **Optimistic Locking:** Detects conflicting modifications before updating records.
- **Triggers:** Automatically execute database operations when specified events occur.
- **Indexes:** Improve data retrieval performance.
- **Transactions:** Maintain reliable and consistent resource allocation.
- **Audit Records:** Maintain a record of resource allocation activities.

These concepts help prevent incorrect inventory updates when multiple resource managers access the system simultaneously.

## 4. Technology Stack

| Component | Technologies |
|---|---|
| Backend | Java 17, Spring Boot |
| Database | MySQL 8.0, InnoDB |
| Frontend | HTML, CSS, JavaScript |
| AI / Machine Learning | Python, Scikit-learn |
| Machine Learning Models | Logistic Regression, Random Forest, Gradient Boosting |
| Authentication | Spring Security, JWT, BCrypt |
| Communication | REST APIs, Server-Sent Events (SSE) |
| Build Tool | Maven |

## 5. System Architecture

The project is organised into the following major components:

- **Frontend:** Provides the interface for users and resource managers.
- **Backend:** Handles requests, priority calculation, authentication and resource allocation.
- **Database:** Stores users, requests, inventory and allocation information.
- **AI Module:** Predicts possible shortages and provides confidence scores.
- **OS Simulation:** Demonstrates scheduling, aging, preemption and deadlock avoidance.
- **Verification:** Contains supporting verification and testing material.

## 6. Project Structure

```text
CRAS/
│
├── backend/          # Java Spring Boot backend
├── frontend/         # User interface
├── ai/               # Machine learning models and prediction
├── db/               # Database scripts and schema
├── os_sim/           # OS scheduling simulations
├── data/             # Project datasets
├── verification/     # Testing and verification
├── docs/             # Project documentation
│
├── README.md
└── scripts/          # Supporting scripts
```

## 7. How CRAS Works

1. Users submit resource requests through the system.
2. The backend receives and stores the requests.
3. The priority calculation module evaluates the requests using relevant factors.
4. The AI module estimates possible resource shortages.
5. The scheduling and allocation logic helps determine the order of allocation.
6. Database transactions and locking maintain inventory consistency.
7. Resource managers can monitor requests, manage inventory and add new resources.

## 8. Example Scenario

Suppose a flood affects a particular region.

- A village requests 20 litres of water.
- A hospital requests 30 litres of water.
- Only 20 litres are currently available.

Instead of automatically prioritising the hospital, CRAS evaluates both requests using severity, number of people affected, location risk, resource scarcity and other priority factors.

The system then allocates the available resources according to the calculated priority and allocation rules. Any unfulfilled request can remain pending until additional resources become available.

This demonstrates how dynamic scheduling can support more informed resource distribution during emergencies.

## 9. Project Novelty

The major contributions of CRAS are:

1. **Dynamic Priority Scheduling:** Resource requests are evaluated using multiple factors instead of a fixed hierarchy.
2. **Starvation Prevention:** Aging helps ensure that lower-priority requests are not ignored indefinitely.
3. **AI-Based Shortage Prediction:** An ensemble machine learning approach estimates resource shortage probability for the next 12 hours.
4. **Confidence-Aware Allocation:** Prediction confidence can be considered alongside shortage probability.
5. **Integration of OS, DBMS and AI:** Scheduling, database concurrency control and machine learning are combined in one emergency resource allocation platform.

## 10. Future Scope

- Integration with real-time disaster management data.
- Use of live weather and geographical information.
- Expansion of the prediction module using larger real-world datasets.
- Integration with government and emergency response organisations.
- Mobile application support.
- Real-time monitoring of resources across multiple locations.
- Improved prediction and allocation using continuously updated data.

## 11. Team T006

| Member | Role |
|---|---|
| Arushi Rawat | Team Leader, Priority Calculation, AI Module and System Integration |
| Vinayak Pathak | Database Design, Backend and API Development |
| Aaman Khan | OS Scheduling and Resource Allocation Logic |
| Tulsi Manjari | Frontend Development and Interface Testing |

## 12. Conclusion

CRAS aims to make emergency resource allocation more efficient, reliable and responsive.

By combining Operating System scheduling concepts, DBMS transaction management and AI-based shortage prediction, the system provides a structured approach to handling multiple resource requests when resources are limited.

**Our goal is simple: allocate the right resources to the right requests at the right time, based on actual urgency.**

---

**Team T006 | CRAS – Smart Community Resource Allocation System**
