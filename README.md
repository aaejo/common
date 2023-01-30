# common
Utilities and bits shared by multiple other repos

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

Scripts (`.sh`, `.sql`, `.sql.gz`, `.sql.xz` and `.sql.zst`) in the `sql/` directory of this repo will be used to initialize the database when the container starts up.  
