# Include INSIDE the existing cloud.f-li.cn:6400 server block in k8s.conf.
# Do not include in another server or expose /internal, /custom, cookies, profiles, or Remoting.
location ^~ /crawler/api/v1/ {
    client_max_body_size 1m;
    proxy_connect_timeout 5s;
    proxy_read_timeout 30s;
    proxy_send_timeout 30s;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Forwarded-Host $http_host;
    proxy_set_header X-Forwarded-Port 6400;
    proxy_set_header X-Forwarded-Prefix /crawler;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Authorization $http_authorization;
    proxy_pass http://127.0.0.1:6020/api/v1/;
    proxy_redirect /api/v1/ /crawler/api/v1/;
}
location = /crawler { return 404; }
location = /crawler/ { return 404; }
