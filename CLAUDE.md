````markdown
# CLAUDE.md

# Enterprise Development Guidelines

> **Mission**
>
> Generate enterprise-grade, production-ready software that can be built, tested, containerized, and deployed to Kubernetes with minimal manual changes. Every design decision should prioritize maintainability, security, scalability, performance, testability, and operational excellence.

---

# Technology Stack

Unless explicitly instructed otherwise, assume the project uses:

## Backend

- Java (Latest LTS)
- Spring Boot (Latest Stable)
- Spring Security
- Spring Data JPA
- Maven

## Frontend

- Angular (Latest Stable)
- TypeScript
- Angular Material (when appropriate)
- RxJS

## Database

- PostgreSQL (preferred)
- Flyway or Liquibase for migrations

## Infrastructure

- Docker
- Kubernetes
- REST APIs
- OpenAPI (Swagger)
- Git

---

# Primary Objectives

Every solution should prioritize:

1. Readability
2. Maintainability
3. Security
4. Performance
5. Scalability
6. Testability
7. Reusability
8. Simplicity
9. Production Readiness

When trade-offs exist, always favor long-term maintainability.

---

# General Coding Principles

Always follow:

- SOLID Principles
- DRY
- KISS
- Separation of Concerns
- Clean Code
- Clean Architecture
- Boy Scout Rule
- Favor Composition over Inheritance

Avoid

- Overengineering
- Premature optimization
- Deep nesting
- Hardcoded values
- Code duplication
- Large God classes
- Large methods

---

# Naming Conventions

## Variables

Use descriptive names.

Good

```java
customerName
totalAmount
isActive
hasPermission
```

Avoid

```java
tmp
obj
data1
flag
x
```

---

## Methods

Methods should perform a single responsibility.

Good

```java
calculateInvoiceTotal()
validateCustomer()
createOrder()
sendNotification()
```

Avoid

```java
process()
execute()
handle()
run()
```

---

## Classes

Use meaningful nouns.

Examples

```
UserController
OrderService
InvoiceRepository
PaymentValidator
UserProfileComponent
```

---

# Code Organization

## Spring Boot

```
Controller
    ↓
Service
    ↓
Repository
    ↓
Database
```

Controllers

- Validate requests
- Delegate work
- Return responses

Services

- Contain business logic

Repositories

- Data access only

---

## Angular

```
Component
    ↓
Service
    ↓
API
```

Components should contain UI logic only.

Business logic belongs in services.

---

# Formatting

- Consistent formatting
- Remove unused imports
- Remove commented code
- Keep methods short
- Keep classes focused
- Prefer early returns
- Limit line length
- Organize imports

---

# Documentation

Document

- Public classes
- Public methods
- Complex business logic
- Important architectural decisions

Avoid documenting obvious code.

---

# Code Reuse

Before writing new code:

- Search existing project code.
- Reuse existing services.
- Reuse existing utilities.
- Reuse existing Angular components.
- Reuse shared libraries.

Never duplicate business logic.

---

# Open Source Libraries

## Do not reinvent the wheel.

Before implementing any complex functionality, determine whether a mature open-source library already exists.

Prefer libraries that are:

- Industry standard
- Well maintained
- Secure
- Actively developed
- Well documented
- Compatible with the project

Examples

| Feature | Preferred Library |
|----------|------------------|
| Object Mapping | MapStruct |
| JSON | Jackson |
| Validation | Hibernate Validator |
| Retry | Spring Retry |
| Resilience | Resilience4j |
| API Documentation | springdoc-openapi |
| Excel | Apache POI |
| PDF | Apache PDFBox |
| Caching | Caffeine / Redis |
| Authentication | Spring Security |
| Database Migration | Flyway / Liquibase |
| Logging | SLF4J + Logback |

Avoid implementing custom solutions for common problems unless explicitly required.

---

# Dependency Management

- Use latest stable versions.
- Remove unused dependencies.
- Avoid deprecated libraries.
- Minimize dependency count.
- Regularly update dependencies.
- Consider CVEs before introducing dependencies.

---

# API Design

Follow REST principles.

Use proper HTTP methods.

```
GET
POST
PUT
PATCH
DELETE
```

Always

- Use DTOs
- Return consistent responses
- Validate requests
- Support pagination
- Version APIs when necessary

---

# Validation

Validation must exist on both frontend and backend.

Backend

- Bean Validation
- Custom validators when needed

Frontend

- Reactive Forms
- User-friendly validation

Never trust client-side validation.

---

# Error Handling

Never swallow exceptions.

Use

- Global Exception Handler
- Custom Exceptions
- Meaningful messages

Never expose

- Stack traces
- Internal details
- Sensitive information

---

# Logging

Log

- Business events
- Warnings
- Errors
- Security events

Never log

- Passwords
- Secrets
- Tokens
- Personal data

---

# Security

Security is mandatory.

## Authentication

- Spring Security
- OAuth2/JWT where appropriate
- Never hardcode secrets

## Authorization

Always validate permissions on the server.

## Input Validation

Validate

- Length
- Format
- Range
- Business rules

## SQL Injection

Always use

- JPA
- Parameterized queries
- Prepared Statements

Never concatenate SQL.

## XSS

- Encode output
- Sanitize HTML
- Avoid unsafe DOM manipulation

## CSRF

Enable CSRF protection where appropriate.

## Cookies

Use

- HttpOnly
- Secure
- SameSite=Lax or Strict

Never store sensitive information inside cookies.

---

# Performance

Backend

- Optimize SQL
- Avoid N+1 queries
- Pagination
- Connection pooling
- Caching
- Async processing where appropriate

Frontend

- Lazy Loading
- OnPush Change Detection
- trackBy
- Reduce API calls
- Optimize bundle size

---

# Scalability

Design for horizontal scaling.

Prefer

- Stateless services
- Distributed caching
- Idempotent APIs
- Event-driven architecture where appropriate

Avoid server-side session state unless necessary.

---

# Database

- Normalize appropriately
- Create indexes
- Optimize queries
- Use transactions correctly
- Flyway/Liquibase migrations

---

# Testing Standards

Testing is mandatory.

## Unit Tests

Every business logic should include tests.

Target:

- Minimum 90% coverage
- Prefer 100% coverage for business logic

Cover

- Happy path
- Error cases
- Validation
- Boundary cases
- Edge cases
- Exceptions

Backend

- JUnit 5
- Mockito
- Spring Boot Test

Frontend

- Jasmine
- Karma (or Jest)

Use

- Arrange
- Act
- Assert

Tests should be

- Fast
- Deterministic
- Independent

---

## Integration Tests

Include where appropriate

- Repository Tests
- Controller Tests
- API Tests
- Testcontainers
- MockMvc
- WebTestClient

---

# Spring Boot Best Practices

- Constructor Injection
- DTO Pattern
- Service Layer
- Repository Pattern
- Bean Validation
- Global Exception Handling
- Configuration Properties
- Profiles
- Transaction Management
- Spring Actuator

---

# Angular Best Practices

- Angular Style Guide
- Standalone Components
- Reactive Forms
- Route Guards
- HTTP Interceptors
- Shared Components
- Shared Services
- Strong Typing
- Avoid using `any`

---

# Cloud Native Development

Applications should be cloud-native by default.

Prefer

- Stateless applications
- Externalized configuration
- Health endpoints
- Graceful shutdown
- Observability
- Horizontal scalability

---

# Docker

Every application should include

- Production-ready Dockerfile
- Multi-stage build
- Small image size
- Non-root user
- Environment-based configuration
- .dockerignore

Never bake secrets into Docker images.

---

# Kubernetes

Applications should be Kubernetes-ready.

Support

- Deployment
- Service
- ConfigMap
- Secret
- Ingress
- Horizontal Pod Autoscaler

Include

- Liveness Probe
- Readiness Probe
- Resource Requests
- Resource Limits
- Rolling Updates

Use Spring Boot Actuator health endpoints.

---

# Observability

Support

- Structured logging
- Health checks
- Metrics
- Micrometer
- Prometheus compatibility

---

# CI/CD

Generated projects should support

- Maven build
- Unit Tests
- Integration Tests
- Static Analysis
- SonarQube
- Dependency Scanning
- Docker Build
- Docker Image Scanning
- Kubernetes Deployment

---

# Code Review Checklist

Before completing any task verify

- Meaningful naming
- Proper formatting
- SOLID principles
- No duplicate logic
- No hardcoded secrets
- Proper validation
- Authorization checks
- SQL Injection prevention
- XSS prevention
- CSRF handled
- Secure cookies
- Exception handling
- Logging
- Documentation
- Unit tests
- Integration tests where applicable
- Performance considered
- Scalability considered
- Latest stable libraries
- Production readiness

---

# Expected Claude Behavior

When generating code, always:

- Produce production-ready code.
- Follow all guidelines in this document.
- Reuse existing project code whenever possible.
- Do not reinvent the wheel.
- Prefer mature open-source libraries.
- Generate secure code by default.
- Include comprehensive input validation.
- Include robust error handling.
- Include meaningful logging.
- Include unit tests for all business logic.
- Maximize practical unit test coverage (target 90%+, 100% for critical business logic where feasible).
- Generate integration tests when appropriate.
- Use the latest stable versions of frameworks and libraries.
- Avoid deprecated APIs.
- Design for scalability and cloud-native deployment.
- Externalize all configuration.
- Keep applications stateless where possible.
- Generate Docker-ready applications.
- Ensure applications are Kubernetes-ready.
- Consider CI/CD requirements during implementation.
- Explain architectural decisions when they are not obvious.

---

# Ultimate Goal

Every generated solution should be ready for the following deployment pipeline:

```
Source Code
      │
      ▼
Compile
      │
      ▼
Static Analysis
      │
      ▼
Unit Tests
      │
      ▼
Integration Tests
      │
      ▼
Package Application
      │
      ▼
Build Docker Image
      │
      ▼
Container Security Scan
      │
      ▼
Push Image to Registry
      │
      ▼
Deploy to Kubernetes
      │
      ▼
Production
```

The delivered solution should require minimal manual work before deployment to a production Kubernetes environment.
````

