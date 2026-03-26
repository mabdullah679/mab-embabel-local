FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package.json
RUN npm install
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY docker-entrypoint.sh /docker-entrypoint.d/40-frontend-hello.sh
RUN chmod +x /docker-entrypoint.d/40-frontend-hello.sh
EXPOSE 80
