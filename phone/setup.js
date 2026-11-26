"use strict";
const byId = (id) => document.getElementById(id);
const token = location.hash.slice(1);
history.replaceState(null, "", location.pathname);
const form = byId("setup");
const method = byId("method");
const sourceFields = ["name", "address", "user", "password", "guide"].map(byId);
function configure() {
  const playlist = method.value === "playlist",
    xtream = method.value === "xtream";
  for (const [id, visible] of Object.entries({
    address: Boolean(method.value),
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
  form.querySelector('button[type="submit"]').disabled = !method.value;
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
    for (const field of sourceFields) {
      if (!field.disabled) data[field.id] = field.value;
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
    for (const field of sourceFields) field.value = "";
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
