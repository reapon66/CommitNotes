import { generateKeyPairSync, randomBytes } from 'node:crypto'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const domain = process.env.COMMITNOTES_DOMAIN
const email = process.env.ADMIN_EMAIL
if (!domain || !/^[a-z0-9.-]+$/.test(domain) || !email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
  throw new Error('Define COMMITNOTES_DOMAIN and ADMIN_EMAIL before generating production credentials')
}
const password = randomBytes(30).toString('hex')
const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
const pub = publicKey.export({ format: 'jwk' })
const priv = privateKey.export({ format: 'jwk' })
const vapidPublic = Buffer.concat([
  Buffer.from([4]), Buffer.from(pub.x, 'base64url'), Buffer.from(pub.y, 'base64url'),
]).toString('base64url')
const entries = {
  DB_PASSWORD: randomBytes(32).toString('hex'),
  APP_SECRET: randomBytes(48).toString('hex'),
  APP_ORIGIN: `https://${domain}`,
  COMMITNOTES_DOMAIN: domain,
  COMPOSE_FILE: 'compose.yaml:compose.prod.yaml',
  ADMIN_EMAIL: email,
  ADMIN_PASSWORD: password,
  VAPID_PUBLIC: vapidPublic,
  VAPID_PRIVATE: priv.d,
  VAPID_SUBJECT: `mailto:${email}`,
}
const dir = join(process.cwd(), '.deploy')
mkdirSync(dir, { recursive: true })
writeFileSync(join(dir, '.env'), Object.entries(entries).map(([key, value]) => `${key}=${value}`).join('\n') + '\n', { mode: 0o600 })
writeFileSync(join(dir, 'admin-access.txt'), `URL: https://${domain}\nE-mail: ${email}\nSenha inicial: ${password}\n`, { mode: 0o600 })
process.stdout.write('Credenciais de produção geradas em .deploy (valores ocultos).\n')
