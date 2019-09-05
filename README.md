# Apache Fineract CN Demo Server [![Build Status](https://api.travis-ci.com/apache/fineract-cn-demo-server.svg?branch=develop)](https://travis-ci.com/apache/fineract-cn-demo-server)
Sample server setup used for demo purposes

## Preconditions
1. Java
2. Gradle
3. Maven
4. Cassandra
5. Postgres

## Steps
1. Git clone the apache fineract-cn-demo-server code

    `git clone https://github.com/apache/fineract-cn-demo-server`
2. Change directory to scripts/dependencies_to_local_maven

    `cd fineract-cn-demo-server/scripts/dependencies_to_local_maven/`
3. Install all the dependencies using Maven, this is required to install all the dependencies required by demo-server

    `mvn package`
4. Go back to your project's parent folder

    `cd ../../`
5. Build the project using gradle wrapper

    `./gradlew publishToMavenLocal`
6. Change directory to build/libs

    `cd build/libs`
7. We have two options to run demo server

    ### i.  Using embedded Postgresql & Cassandra
      a. If you are running for the first time then run below command
          
          java -jar -Ddemoserver.provision=true -Ddemoserver.lite=true demo-server-0.1.0-BUILD-SNAPSHOT.jar
      b. If you have already succesfully ran the first step and you are starting the server for the second time then run below command
      
          java -jar -Ddemoserver.lite=true demo-server-0.1.0-BUILD-SNAPSHOT.jar
  
    ### ii. Using external Postgresql & Cassandra
      a. If you are running for the first time then run below command
        
          java -jar -Ddemoserver.persistent=true -Ddemoserver.provision=true -Ddemoserver.lite=true -Dcustom.cassandra.contactPoints=127.0.0.1:9042 -Dcassandra.cluster.user=cassandra -Dcassandra.cluster.pwd=password -Dcustom.postgresql.host=127.0.0.1 -Dcustom.postgresql.port=5432 -Dcustom.postgresql.user=postgres -Dcustom.postgresql.password=password demo-server-0.1.0-BUILD-SNAPSHOT.jar
      b. If you have already succesfully ran the first step and you are starting the server for the second time then run below command
          
          java -jar -Ddemoserver.persistent=true -Ddemoserver.lite=true -Dcustom.cassandra.contactPoints=127.0.0.1:9042 -Dcassandra.cluster.user=cassandra -Dcassandra.cluster.pwd=password -Dcustom.postgresql.host=127.0.0.1 -Dcustom.postgresql.port=5432 -Dcustom.postgresql.user=postgres -Dcustom.postgresql.password=password demo-server-0.1.0-BUILD-SNAPSHOT.jar
      
The following log statement signals the completion of the build:

`INFO  o.e.jetty.server.AbstractConnector - Stopped ServerConnector@1bdb0376{HTTP/1.1,[http/1.1]}`
    

#### Supported Environment Variables

Sample usage: `java -jar -Ddemoserver.persistent=true demo-server-0.1.0-BUILD-SNAPSHOT.jar`

##### demoserver.persistent (true/false)
Run in persistent mode and to NOT use embedded datastores

##### demoserver.provision (true/false)
Run the provision steps against the services to bootstrap tenants

##### demoserver.lite (true/false)
Enabling lite mode (defaults to false) restricts the working set of micro-services to Provisioner, Identity, Rhythm, Organization and Customer

##### custom.cassandra.contactPoints
Custom cassandra contact points (multiple values allowed separated by comma e.g. 127.0.0.1:9042,127.0.0.2:9042)

##### cassandra.cluster.user
cassandra user to use

##### cassandra.cluster.pwd
cassandra password to use

##### custom.postgresql.host
postgresql host to use

##### custom.postgresql.user
postgresql user to use

##### custom.postgresql.password
postgresql password to use
