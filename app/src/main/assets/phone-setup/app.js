/* Local-only companion. No remote scripts, analytics, storage, provider calls or key-bearing URLs. */
'use strict';
const $ = id => document.getElementById(id);
const pairing = new URLSearchParams(location.hash.slice(1));
const token = pairing.get('token');
const publicKey = pairing.get('publicKey');
let submitted = false;
function status(message, error = false) { $('status').textContent = message; $('status').dataset.error = String(error); }
function errorMessage(error) {
  if (error.name === 'AbortError' || /Failed to fetch|Load failed|NetworkError/i.test(error.message || ''))
    return 'Cannot reach the TV. Put both devices on the same private home network. Guest Wi-Fi, VPNs and device isolation may block setup.';
  return error.message || 'Could not connect. Reopen phone setup on the TV and scan the new QR code.';
}
async function request(path, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 6000);
  let response;
  try { response = await fetch(path, {...options, cache: 'no-store', credentials: 'omit', redirect: 'error',
    signal: controller.signal, headers: {'X-Hearth-Pairing': token, ...(options.headers || {})}}); }
  finally { clearTimeout(timeout); }
  if (!response.ok) throw new Error(response.status === 409 ? 'A setup is already waiting on the TV. Approve or reject it there first.' :
    response.status === 403 ? 'This QR session is not authorized. Scan the current QR code on the TV.' : 'The TV could not accept this setup. Reopen phone setup and scan again.');
  return response.json();
}
async function poll() {
  if (!submitted) return;
  try {
    const value = await request('/api/status');
    if (value.state === 'complete') { status('Saved on your TV. You can close this page.'); return; }
    if (value.state === 'waiting') { submitted = false; $('send').disabled = false; $('setup').removeAttribute('aria-busy'); status('The TV rejected the setup. Review your choices, paste the key again if needed, and resend.', true); return; }
    if (value.state === 'expired' || value.state === 'closed') throw new Error('This setup session has ended. Reopen phone setup on the TV.');
    status(value.state === 'saving' ? 'The TV is saving your setup…' : 'Sent. Use OK on your TV remote to approve the setup.');
    setTimeout(poll, 1000);
  } catch (error) { status('Cannot reach the TV. Check its screen before sending again. Guest Wi-Fi, VPNs or device isolation may block the connection.', true); }
}
$('setup').addEventListener('submit', async event => {
  event.preventDefault(); if (submitted) return;
  $('send').disabled = true; $('setup').setAttribute('aria-busy', 'true');
  let value;
  try {
    value = {key: $('key').value.trim(), source: $('source').value, mode: $('mode').value, target: $('target').value,
      voice: $('voice').value, readAloud: $('readAloud').value === 'true', volume: Number($('volume').value)};
    const envelope = HearthSetupCrypto.encrypt(value, publicKey, token);
    value.key = ''; $('key').value = '';
    await request('/api/setup', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(envelope)});
    submitted = true; status('Sent. Approve the setup on your TV.'); poll();
  } catch (error) {
    if (value) value.key = ''; $('key').value = '';
    status(errorMessage(error), true);
    $('send').disabled = false; $('setup').removeAttribute('aria-busy');
  }
});
(async () => {
  try {
    if (!token || !publicKey || token.length !== 43) throw new Error('Scan the QR code shown by Hearth on your TV to open a current setup session.');
    $('code').textContent = token.slice(0, 6).toUpperCase();
    const defaults = await request('/api/defaults');
    for (const language of defaults.languages) { const option = document.createElement('option'); option.value = language.code; option.textContent = language.label; $('target').append(option); }
    for (const name of ['source', 'mode', 'target', 'voice', 'readAloud', 'volume']) $(name).value = String(defaults[name]);
    if ($('volume').selectedIndex < 0) { const option = document.createElement('option'); option.value = String(defaults.volume); option.textContent = String(defaults.volume); $('volume').append(option); $('volume').value = option.value; }
    $('send').disabled = false; status('Ready. Paste your key, choose your settings, then send them to the TV.');
  } catch (error) { status(errorMessage(error), true); }
})();
