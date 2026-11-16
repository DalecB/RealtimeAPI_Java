import { useState, type FormEvent, type ReactNode } from 'react'
import { login } from './api'

// 콘솔 전체가 /internal/** 폴링에 의존하므로, 관리자 로그인 전에는 대시보드를 마운트하지 않는다.
export default function LoginGate({ children }: { children: ReactNode }) {
  const [authed, setAuthed] = useState(false)
  const [externalId, setExternalId] = useState('admin')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

  if (authed) return <>{children}</>

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setFailed(false)
    try {
      await login(externalId, password)
      setAuthed(true)
    } catch {
      setFailed(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="app">
      <main className="page" style={{ maxWidth: 400, margin: '15vh auto 0' }}>
        <form className="card firer-fields" onSubmit={submit}>
          <div className="card-title-row">
            <span className="title">Admin login</span>
            <span className="meta">POST /auth/login</span>
          </div>
          <label>
            <span>Username</span>
            <input value={externalId} onChange={(e) => setExternalId(e.target.value)} autoComplete="username" required />
          </label>
          <label>
            <span>Password</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              autoFocus
            />
          </label>
          {failed && <div className="metric-note" style={{ color: 'var(--color-red)' }}>Invalid username or password</div>}
          <button className="bootstrap-btn" type="submit" disabled={busy}>
            Sign in
          </button>
        </form>
      </main>
    </div>
  )
}
