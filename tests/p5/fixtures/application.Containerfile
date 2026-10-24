FROM python:3.13-alpine
ARG APP_VERSION=v1
ARG APP_BROKEN=false
COPY http-app.py /app/http-app.py
ENV APP_VERSION=${APP_VERSION}
ENV APP_BROKEN=${APP_BROKEN}
EXPOSE 8080
CMD ["python3", "-u", "/app/http-app.py"]
