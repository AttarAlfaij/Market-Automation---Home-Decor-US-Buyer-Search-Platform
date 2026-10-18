# MarketAuto – Export Marketing Automation System

MarketAuto is a web-based application built with Java and Spring Boot to help exporters manage international buyer research, organize buyer information, create email campaigns, and track email activity from one place.

## Features

- **Buyer Search:** Find potential international buyers based on product and location using the Firecrawl API.
- **Buyer Management:** Store, organize, review, and manage buyer and company information.
- **Campaign Management:** Create and manage product-focused wholesale and export campaigns.
- **Email Campaigns:** Prepare and send personalized business emails to selected buyers.
- **Email Tracking:** View email statuses and filter email activity by date range.
- **Buyer Location:** View available buyer location details, including city and state.
- **Data Validation:** Validate buyer information and filter duplicate or irrelevant records.
- **Dashboard:** Access buyer, campaign, and email activity information through a web interface.
- **Authentication:** Planned or in development, depending on the current implementation.
- **Individual API Configuration:** Planned or in development support for user-specific Firecrawl API keys.

## Technology Stack

**Backend**
- Java
- Spring Boot
- Spring MVC
- Spring Data JPA
- Hibernate ORM
- REST APIs

**Database**
- MySQL

**Frontend**
- HTML
- CSS
- JavaScript

**Tools and Integrations**
- Firecrawl API
- Maven
- Git and GitHub
- Postman
- MySQL Workbench

## Application Workflow

1. Search for potential buyers using a product and target location.
2. Review and validate the available buyer information.
3. Save relevant buyers in the database.
4. Create a campaign with product and wholesale details.
5. Prepare and send emails to selected buyers.
6. Monitor email statuses and review activity within a selected date range.

## Prerequisites

Before running the project, install:

- JDK compatible with the project's configured Java version
- Maven
- MySQL Server
- Git
- A Firecrawl API key
- Gmail SMTP credentials or another supported email provider's credentials

## Setup and Installation

### 1. Clone the repository

```bash
git clone YOUR_GITHUB_REPOSITORY_URL
cd YOUR_PROJECT_FOLDER
```

Replace the placeholders with your actual repository URL and project folder.

### 2. Create the MySQL database

Create a database in MySQL using the database name configured in your application.

```sql
CREATE DATABASE marketauto_db;
```

If your project already uses another database name, keep the existing configuration instead.

### 3. Configure environment variables

Configure your database credentials, Firecrawl API key, and email provider credentials using environment variables or a local, untracked configuration file.

Example:

```properties
DB_URL=jdbc:mysql://localhost:3306/marketauto_db
DB_USERNAME=your_mysql_username
DB_PASSWORD=your_mysql_password
FIRECRAWL_API_KEY=your_firecrawl_api_key
MAIL_USERNAME=your_email_address
MAIL_PASSWORD=your_email_app_password
```

These are example variable names. Match them to the actual configuration used in your project.

**Security:** Never commit API keys, email app passwords, database passwords, or other secrets to GitHub.

### 4. Run the application

Using Maven:

```bash
mvn spring-boot:run
```

Alternatively, build the project:

```bash
mvn clean package
```

Then run the generated JAR from the `target` directory.

```bash
java -jar target/your-application.jar
```

Open the application at the localhost URL and port configured in your project.

## Configuration Notes

- Configure Firecrawl before using buyer search.
- Configure SMTP credentials before sending email campaigns.
- Ensure MySQL is running and the database configuration is correct.
- Back up your database before changing schemas or applying migrations.
- If deploying, configure persistent database storage and keep secrets in the hosting provider's environment settings.

## Future Enhancements

- Secure user registration and authentication
- User-specific Firecrawl API key management
- Improved campaign analytics and reporting
- Additional buyer filtering and search improvements
- Production deployment and account-level data isolation

## Author

**Alfaij Attar**

GitHub: [AttarAlfaij](https://github.com/AttarAlfaij)

## License

Choose and add an appropriate open-source license if you intend to allow others to use, modify, or distribute this project.
