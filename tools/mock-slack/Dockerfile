# 멀티스테이지 (spec I9): Go 빌드 → 최소 런타임
FROM golang:1.27.3-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -o /out/mock-slack .

FROM alpine:3.23.7
COPY --from=build /out/mock-slack /usr/local/bin/mock-slack
EXPOSE 8070
ENTRYPOINT ["/usr/local/bin/mock-slack"]
