#!/bin/sh
set -eu

case "${DATABASE_URL:-}" in
    postgresql://*|postgres://*)
        database_url=${DATABASE_URL#*://}
        case "$database_url" in
            *@*)
                database_credentials=${database_url%%@*}
                database_url=${database_url#*@}
                DATABASE_USERNAME=${database_credentials%%:*}
                DATABASE_PASSWORD=${database_credentials#*:}
                export DATABASE_USERNAME DATABASE_PASSWORD
                ;;
        esac
        export DATABASE_URL="jdbc:postgresql://${database_url}"
        ;;
esac

exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar