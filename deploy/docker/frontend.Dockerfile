# syntax=docker/dockerfile:1.7

FROM node:22.23.1-alpine3.24 AS build
WORKDIR /workspace

COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund

COPY frontend ./
ARG VITE_API_BASE_URL=/api
ARG VITE_DEFAULT_CITY=杭州
ARG VITE_AMAP_JS_KEY=
ARG VITE_AMAP_SECURITY_CODE=
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_DEFAULT_CITY=${VITE_DEFAULT_CITY} \
    VITE_AMAP_JS_KEY=${VITE_AMAP_JS_KEY} \
    VITE_AMAP_SECURITY_CODE=${VITE_AMAP_SECURITY_CODE}
RUN npm run build

FROM nginx:1.29.8-alpine
COPY deploy/nginx/citycopilot-frontend.conf /etc/nginx/conf.d/default.conf
COPY deploy/nginx/citycopilot-upstreams.conf /etc/nginx/conf.d/upstreams.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
