"use strict";
const byId = (id) => document.getElementById(id);
const token = location.hash.slice(1);
history.replaceState(null, "", location.pathname);
const form = byId("setup");
const method = byId("method");
function configure() {
  const playlist = method.value === "playlist",
    xtream = method.value === "xtream";
  for (const [id, visible] of Object.entries({
    name: playlist || xtream,
    guide: playlist,
    user: xtream || method.value === "jellyfin",
    password: xtream || method.value === "jellyfin",
  })) {
    const field = byId(id);
    field.parentElement.hidden = !visible;
    field.disabled = !visible;
    field.required = visible && id !== "guide" && (id !== "password" || xtream);
  }
  byId("addressLabel").textContent = playlist
    ? "Playlist URL or Gist"
    : "Server address";
}
function finish(message) {
  form.reset();
  form.replaceChildren(byId("message"));
  byId("message").textContent = message;
}
let pending = false;
async function send(cancel) {
  if (pending) return;
  pending = true;
  const data = { method: cancel ? "cancel" : method.value };
  if (!cancel)
    for (const id of ["name", "address", "user", "password", "guide"]) {
      if (!byId(id).disabled) data[id] = byId(id).value;
    }
  try {
    const response = await fetch("/finish", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer " + token,
      },
      body: JSON.stringify(data),
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
    });
    if (response.ok)
      finish(
        cancel
          ? "Setup canceled. You can close this page."
          : "Continue on your TV. You can close this page.",
      );
    else if (response.status === 400)
      byId("message").textContent = "Check the required fields and try again.";
    else finish("Scan a new code on your TV to start again.");
  } catch {
    finish("Check your TV. If setup did not continue, scan a new code.");
  } finally {
    pending = false;
  }
}
let source = method.value;
method.onchange = () => {
  const selected = method.value;
  const jellyfin = (value) => value === "quick" || value === "jellyfin";
  if (!(jellyfin(source) && jellyfin(selected))) {
    for (const id of ["name", "address", "user", "password", "guide"])
      byId(id).value = "";
  }
  source = selected;
  configure();
};
form.onsubmit = (e) => {
  e.preventDefault();
  send(false);
};
byId("cancel").onclick = () => send(true);
configure();
if (!token) finish("Scan the code on your TV to open a new setup session.");
