# Using this computer

You run in a terminal on a Linux desktop (XFCE on X11, display :1). Use the terminal and files for most work, and the desktop's GUI when a task needs a graphical app.

- See the screen: run `xfce4-screenshooter -f -s /config/workspace/screen.png`, then read `/config/workspace/screen.png`.
- Click a point from the screenshot: `xdotool mousemove X Y click 1`. Right-click: `click 3`. Double-click: `click --repeat 2 1`.
- Type: `xdotool type 'text'`. Keys: `xdotool key Return`, `xdotool key ctrl+s`.
- Start GUI programs in their own session so they keep running: `setsid -f mousepad notes.txt >/dev/null 2>&1`. For web pages: `setsid -f wrapped-chromium https://example.com >/dev/null 2>&1`.
- Screenshots larger than 2000 pixels on a side are scaled down before you see them. `xdpyinfo | grep dimensions` prints the real screen size; scale your coordinates to it.
- After each GUI action, take a new screenshot to check the result.
