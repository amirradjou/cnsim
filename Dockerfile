# CNSim in a container: the simulator jar plus the shipped configurations.
#
#   docker build -t cnsim .
#   docker run --rm --user "$(id -u):$(id -g)" -v "$PWD/out:/cnsim/out" cnsim \
#       -c examples/networks/litecoin.properties --sims 10
#
# Output goes to /cnsim/out/... as set by each config (mount it to keep the logs; --user keeps
# the files owned by you rather than root).
# The analysis tool (tools/finality) runs on the host with uv.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
# The whole checkout, history included, so the build can record the commit (and whether the
# tree had uncommitted changes) for run provenance.
COPY . .
RUN mvn -B -ntp -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /cnsim
COPY --from=build /src/target/cnsim-0.0.1-SNAPSHOT.jar cnsim.jar
# Configs name their input files relative to the repository root.
COPY --from=build /src/src/main/resources src/main/resources
COPY --from=build /src/examples examples
COPY --from=build /src/tools/golden tools/golden
ENTRYPOINT ["java", "-jar", "/cnsim/cnsim.jar"]
CMD ["--help"]
