# common
Utilities and bits shared by multiple other repos

# Deployment

This repo contains the orchestration for deploying and running the entire project system. The full system environment is described in `docker-compose.yaml`, and can be launched by executing `docker compose up -d`.  

Configuration of the system components is ideally carried out through the `docker-compose.yaml` file by setting environment variables. See the configuration section of the user manual for more information.

Scripts (`.sh`, `.sql`, `.sql.gz`, `.sql.xz` and `.sql.zst`) in the `deploy/sql/` directory of this repo will be used to initialize the database when the system starts up.  

New institution data files can be placed in the `deploy/institutions-data/` directory to update the JSON source files for the file-based institution-finder modules without requiring a rebuild.

---

# Developer Resources

## Common Java Library

This repo contains common Java code to be shared between project components. It is split into multiple Maven modules, with the root `pom.xml` acting as the parent and build reactor. The modules are:

- **`jds-common-client`**: A wrapper around Jsoup connection functionality optimized for our project's use case. It can be configured with retry logic using Spring Retry, and respects robots.txt rules including the Crawl-delay directive.
- **`jds-common-messaging`**: Records for use in de/serialization of messages between system modules.

### Maven Setup

The library is published to a GitHub Packages repository associated with this repo. In order to install the package from here, the following steps must be taken:  

0. **Install Maven.** This is a prerequisite for working on this project.  
1. **Create a GitHub personal access token (classic).** It must have at least the `read:packages` permission, as well as `write:packages` if you want to publish new versions of this library. More information can be found [here](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token).  
2. **Access your Maven settings file.** In your user home directory (any OS), find the file `.m2/settings.xml`. If you do not already have one, create one.  
3. **Add the server, repo, and credentials to your Maven settings.** If your settings file is empty, copy the following into it, otherwise modify it as needed to add the necessary sections. Replace `YOUR_GITHUB_USER` with your GitHub username and `YOUR_GITHUB_TOKEN` with your personal access token from step 1.  
   ```xml
   <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                      http://maven.apache.org/xsd/settings-1.0.0.xsd">

     <activeProfiles>
       <activeProfile>aaejo</activeProfile>
     </activeProfiles>

     <profiles>
       <profile>
         <id>aaejo</id>
         <repositories>
           <repository>
             <id>central</id>
             <url>https://repo1.maven.org/maven2</url>
           </repository>
           <repository>
             <id>aaejo-common</id>
             <url>https://maven.pkg.github.com/aaejo/common</url>
             <snapshots>
               <enabled>true</enabled>
             </snapshots>
           </repository>
         </repositories>
       </profile>
     </profiles>

     <servers>
       <server>
         <id>aaejo-common</id>
         <username>YOUR_GITHUB_USER</username>
         <password>YOUR_GITHUB_TOKEN</password>
       </server>
     </servers>
   </settings>
   ```

## Dev Compose File

The file `docker-compose.dev.yaml` can be used to spin up a development/testing environment. It includes both Kafka and MariaDB, configured for easy use with application code **not** running in containers (i.e. when running/debugging from IDE). It also includes web-based DB (Adminer) and Kafka (Redpanda Console) clients.  

To bring up the containers, run `docker compose -f docker-compose.dev.yaml up -d`. To tear everything down, run `docker compose -f docker-compose.dev.yaml down`.  

The compose file defines a few port-forwards for development use, these will all be on your `localhost` when the containers are running:

| Port | Name          | Use                                                                         |
| ---- | ------------- | --------------------------------------------------------------------------- |
| 9092 | Kafka Broker  | Kafka broker port for application use                                       |
| 3306 | DB Server     | MariaDB connection port for application use and/or your preferred DB client |
| 8080 | DB Console    | Access from your browser to access Adminer                                  |
| 8081 | Kafka Console | Access from your browser to access Redpanda Console                         |

None of the containers have persistent volumes, data will be lost on container restart.  

Scripts (`.sh`, `.sql`, `.sql.gz`, `.sql.xz` and `.sql.zst`) in the `deploy/sql/` directory of this repo will be used to initialize the database when the container starts up.  
