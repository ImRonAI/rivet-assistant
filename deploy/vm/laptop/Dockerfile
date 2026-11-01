FROM lscr.io/linuxserver/webtop:debian-xfce-536c698c-ls219
COPY --from=toolbox install.sh /tmp/toolbox.sh
# Chromium's system libraries for OpenCode's Playwright MCP too, as in the computer image.
RUN sh /tmp/toolbox.sh && rm /tmp/toolbox.sh \
    && npm_config_cache=/tmp/npm-cache npx --yes playwright@1.64.0-alpha-1790635538000 install-deps chromium \
    && rm -rf /tmp/npm-cache
