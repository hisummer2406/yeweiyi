# syntax=docker/dockerfile:1
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# 纯静态二进制：运行镜像不含 libc，也不需要联网下载任何东西（离线部署要求）。
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/server ./cmd/server

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/server /server
# 配置只走环境变量或挂载的 /config.yaml（--config），镜像内不带站点密钥。
USER nonroot:nonroot
ENTRYPOINT ["/server"]
