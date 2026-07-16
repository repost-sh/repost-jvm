ARG SERVER_IMAGE
FROM ${SERVER_IMAGE}
ARG EE_FEATURE=jakartaee-10.0
COPY --chown=1001:0 server.xml /config/server.xml
RUN sed -i "s/@EE_FEATURE@/${EE_FEATURE}/" /config/server.xml && features.sh
COPY --chown=1001:0 repost.war /config/dropins/repost.war
