import { useEffect, useMemo, useRef, useState } from 'react';

const STORAGE_KEY = 'domains-api-chat-sessions';
const ORCHESTRATOR_URL = 'http://localhost:8081';

function createSession(name = 'New Chat') {
  return {
    id: crypto.randomUUID(),
    name,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    messages: []
  };
}

function loadSessions() {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (!saved) {
    return [createSession('Spring Consumer Chat')];
  }
  try {
    const parsed = JSON.parse(saved);
    return Array.isArray(parsed) && parsed.length > 0 ? parsed : [createSession('Spring Consumer Chat')];
  } catch {
    return [createSession('Spring Consumer Chat')];
  }
}

function deriveSessionName(query) {
  const cleaned = query.trim().replace(/\s+/g, ' ');
  return cleaned.slice(0, 42) || 'New Chat';
}

function buildConversationHistory(messages) {
  return messages.flatMap((message) => {
    if (message.role === 'user') {
      return [{ role: 'user', content: message.content }];
    }
    if (message.role === 'assistant') {
      return [{ role: 'assistant', content: message.content }];
    }
    return [];
  });
}

function App() {
  const [sessions, setSessions] = useState(loadSessions);
  const [activeSessionId, setActiveSessionId] = useState(() => loadSessions()[0].id);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('Connected to the orchestrator.');
  const messageListRef = useRef(null);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions));
  }, [sessions]);

  useEffect(() => {
    if (!sessions.some((session) => session.id === activeSessionId) && sessions[0]) {
      setActiveSessionId(sessions[0].id);
    }
  }, [activeSessionId, sessions]);

  useEffect(() => {
    messageListRef.current?.scrollTo({ top: messageListRef.current.scrollHeight, behavior: 'smooth' });
  }, [sessions, loading]);

  const activeSession = useMemo(
    () => sessions.find((session) => session.id === activeSessionId) || sessions[0],
    [activeSessionId, sessions]
  );

  async function submitQuery(event) {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed || loading || !activeSession) {
      return;
    }

    const userMessage = {
      id: crypto.randomUUID(),
      role: 'user',
      content: trimmed,
      createdAt: new Date().toISOString()
    };

    setSessions((current) => current.map((session) => (
      session.id === activeSession.id
        ? {
          ...session,
          name: session.messages.length === 0 ? deriveSessionName(trimmed) : session.name,
          updatedAt: new Date().toISOString(),
          messages: [...session.messages, userMessage]
        }
        : session
    )));

    setQuery('');
    setLoading(true);
    setError('');
    setStatus('Calling the Spring consumer flow...');

    try {
      const response = await fetch(`${ORCHESTRATOR_URL}/agent/query`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: trimmed,
          history: buildConversationHistory(activeSession.messages)
        })
      });

      if (!response.ok) {
        throw new Error(`Request failed with status ${response.status}`);
      }

      const result = await response.json();
      const assistantMessage = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: result.result || 'No response received.',
        traces: Array.isArray(result.traces) ? result.traces : [],
        createdAt: new Date().toISOString(),
        status: result.status || 'COMPLETED'
      };

      setSessions((current) => current.map((session) => (
        session.id === activeSession.id
          ? {
            ...session,
            updatedAt: new Date().toISOString(),
            messages: [...session.messages, assistantMessage]
          }
          : session
      )));
      setStatus('Response received from the Spring consumer flow.');
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : 'Request failed.');
      setStatus('The orchestrator request failed.');
    } finally {
      setLoading(false);
    }
  }

  function createNewChat() {
    const session = createSession();
    setSessions((current) => [session, ...current]);
    setActiveSessionId(session.id);
    setQuery('');
    setError('');
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-card">
          <p className="eyebrow">Local Chat</p>
          <h1>Spring Consumer Console</h1>
          <p>
            The UI is chat-only. The backend reads the Spring consumer REST API and answers from those records.
          </p>
          <button type="button" className="primary-button" onClick={createNewChat}>New Chat</button>
        </div>

        <div className="session-list">
          {sessions.map((session) => (
            <button
              type="button"
              key={session.id}
              className={session.id === activeSessionId ? 'session-card active' : 'session-card'}
              onClick={() => setActiveSessionId(session.id)}
            >
              <strong>{session.name}</strong>
              <span>{session.messages.length} messages</span>
            </button>
          ))}
        </div>
      </aside>

      <main className="chat-shell">
        <header className="chat-header">
          <div>
            <p className="eyebrow">Chatbot</p>
            <h2>Ask about Spring consumer records</h2>
            <p className="header-copy">
              Examples: <code>show record 4</code>, <code>what is synced right now?</code>, <code>summarize the latest merged data</code>.
            </p>
          </div>
          <div className="status-pill">{status}</div>
        </header>

        <section className="messages" ref={messageListRef}>
          {activeSession?.messages.length ? activeSession.messages.map((message) => (
            <article key={message.id} className={message.role === 'user' ? 'message user' : 'message assistant'}>
              <div className="message-meta">
                <strong>{message.role === 'user' ? 'You' : 'Assistant'}</strong>
                <span>{new Date(message.createdAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}</span>
              </div>
              <p>{message.content}</p>

              {message.role === 'assistant' && Array.isArray(message.traces) && message.traces.length > 0 && (
                <details className="trace-block">
                  <summary>Trace</summary>
                  <div className="trace-list">
                    {message.traces.map((trace, index) => (
                      <div key={`${trace.tool}-${index}`} className="trace-item">
                        <strong>{trace.tool}</strong>
                        <span>{trace.success ? 'ok' : 'error'}</span>
                        <pre>{trace.outputSummary}</pre>
                      </div>
                    ))}
                  </div>
                </details>
              )}
            </article>
          )) : (
            <div className="empty-state">
              <strong>No messages yet.</strong>
              <p>Start with a question about the records exposed by the Spring consumer API on port 8080.</p>
            </div>
          )}
        </section>

        <form className="composer" onSubmit={submitQuery}>
          <textarea
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Ask about a record, sync state, or a specific id..."
            rows={4}
          />
          <div className="composer-bar">
            {error && <span className="error-text">{error}</span>}
            <button type="submit" className="primary-button" disabled={loading}>
              {loading ? 'Working...' : 'Send'}
            </button>
          </div>
        </form>
      </main>
    </div>
  );
}

export default App;
