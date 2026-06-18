// Downloads the latest jstall.jar from GitHub Releases at build time.
// Caches the JAR in lib/ and skips re-download if version matches.

const https = require('https');
const fs = require('fs');
const path = require('path');

const LIB_DIR = path.join(__dirname, '..', 'lib');
const JAR_PATH = path.join(LIB_DIR, 'jstall.jar');
const VERSION_PATH = path.join(LIB_DIR, '.version');
const API_URL = 'https://api.github.com/repos/parttimenerd/jstall/releases/latest';

/**
 * Simple HTTPS fetch with redirect following.
 * Returns { status, body: Buffer }.
 */
function fetch(url) {
    return new Promise((resolve, reject) => {
        https.get(url, { headers: { 'User-Agent': 'vscode-jstall-build' } }, (res) => {
            if (res.statusCode === 301 || res.statusCode === 302) {
                return fetch(res.headers.location).then(resolve, reject);
            }
            const chunks = [];
            res.on('data', (chunk) => chunks.push(chunk));
            res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks) }));
            res.on('error', reject);
        }).on('error', reject);
    });
}

async function main() {
    console.log('Checking for latest jstall release...');

    const releaseRes = await fetch(API_URL);
    if (releaseRes.status !== 200) {
        throw new Error(`GitHub API returned ${releaseRes.status}: ${releaseRes.body.toString().slice(0, 200)}`);
    }

    const release = JSON.parse(releaseRes.body.toString());
    const version = release.tag_name; // e.g. "v0.5.3"

    // Skip download if we already have this version
    if (fs.existsSync(VERSION_PATH) && fs.existsSync(JAR_PATH)) {
        const cached = fs.readFileSync(VERSION_PATH, 'utf8').trim();
        if (cached === version) {
            console.log(`jstall ${version} already downloaded (${(fs.statSync(JAR_PATH).size / 1024 / 1024).toFixed(1)} MB).`);
            return;
        }
    }

    const asset = release.assets.find(a => a.name === 'jstall.jar');
    if (!asset) {
        throw new Error(`No jstall.jar found in release ${version}. Assets: ${release.assets.map(a => a.name).join(', ')}`);
    }

    const sizeMB = (asset.size / 1024 / 1024).toFixed(1);
    console.log(`Downloading jstall ${version} (${sizeMB} MB)...`);

    if (!fs.existsSync(LIB_DIR)) {
        fs.mkdirSync(LIB_DIR, { recursive: true });
    }

    const jarRes = await fetch(asset.browser_download_url);
    if (jarRes.status !== 200) {
        throw new Error(`Download failed with status ${jarRes.status}`);
    }

    fs.writeFileSync(JAR_PATH, jarRes.body);
    fs.writeFileSync(VERSION_PATH, version);

    console.log(`Downloaded jstall ${version} to lib/jstall.jar (${(jarRes.body.length / 1024 / 1024).toFixed(1)} MB).`);
}

main().catch(err => {
    console.error('Failed to download jstall:', err.message);
    if (fs.existsSync(JAR_PATH)) {
        console.log('Using previously downloaded JAR.');
    } else {
        console.error('No cached JAR available. Build will fail.');
        process.exit(1);
    }
});
