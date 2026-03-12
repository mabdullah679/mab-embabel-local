import { Fragment, useEffect, useMemo, useRef, useState } from 'react';

const pages = ['Agent Console', 'Calendar Viewer', 'Email Mockup', 'Metadata Inspector'];
const STORAGE_KEY = 'mab-embabel-chat-sessions';
const SYSTEM_STATE_KEY = 'mab-embabel-database-id';
const ORCHESTRATOR_URL = 'http://localhost:8081';
const TOOLS_URL = 'http://localhost:8082';
const AGENT_REQUEST_TIMEOUT_MS = 60000;

function createSession(name = 'New Chat') {
  return {
    id: crypto.randomUUID(),
    name,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    messages: []
  };
}

function normalizeSessions(value) {
  return Array.isArray(value) && value.length > 0 ? value : [createSession('First Chat')];
}

function tryParseJson(value) {
  if (typeof value !== 'string') {
    return null;
  }
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function formatTracePayload(value) {
  if (typeof value !== 'string') {
    return JSON.stringify(value, null, 2);
  }
  const parsed = tryParseJson(value);
  return parsed ? JSON.stringify(parsed, null, 2) : value;
}

function deriveSessionName(query) {
  return query.trim().replace(/\s+/g, ' ');
}

function buildConversationHistory(messages) {
  return (messages || [])
    .flatMap((message) => {
      if (message.role === 'user') {
        return [{ role: 'user', content: message.query }];
      }
      if (message.role === 'assistant') {
        return [{
          role: 'assistant',
          content: String(message.result?.result ?? ''),
          pendingClarification: message.result?.pendingClarification || null
        }];
      }
      return [];
    })
    .filter((turn) => turn.content && turn.content.trim().length > 0);
}

function latestPendingClarification(messages) {
  for (let index = (messages || []).length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message?.role === 'assistant' && message?.result?.status === 'CLARIFICATION' && message?.result?.pendingClarification) {
      return message.result.pendingClarification;
    }
  }
  return null;
}

function messageOutcomeTone(result) {
  switch (result?.status) {
    case 'COMPLETED':
      return 'success';
    case 'CLARIFICATION':
      return 'warning';
    case 'VALIDATION_ERROR':
      return 'error';
    default:
      return 'neutral';
  }
}

function messageOutcomeLabel(result) {
  switch (result?.status) {
    case 'COMPLETED':
      return 'Completed';
    case 'CLARIFICATION':
      return 'Clarification';
    case 'VALIDATION_ERROR':
      return 'Validation Error';
    default:
      return 'Assistant';
  }
}

function parseEmailDraft(value) {
  if (typeof value === 'object' && value?.recipient && value?.subject && value?.body) {
    const signer = value?.senderName || '[Sender Name]';
    const closing = `${value?.tone === 'casual' ? 'Best' : 'Regards'},\n${signer}`;
    const body = String(value.body || '')
      .replace(/\n*(Best regards|Regards|Best),?\s*\n(?:\[?Your Name\]?|[A-Za-z][^\n]*)\s*$/i, '')
      .trim();
    const draft = `To: ${value.recipient}\nSubject: ${value.subject}\n\n${buildGreetingFromRecipients(value.recipient)}\n\n${body}\n\n${closing}`;
    return {
      to: value.recipient,
      senderName: signer,
      subject: value.subject,
      body,
      closing,
      draft
    };
  }

  if (typeof value === 'object' && value?.draft) {
    return parseEmailDraft(value.draft);
  }

  const parsedJson = tryParseJson(value);
  if (parsedJson?.draft) {
    return parseEmailDraft(parsedJson.draft);
  }

  if (typeof value !== 'string' || !value.startsWith('To: ')) {
    return null;
  }

  const lines = value.split('\n');
  const to = lines[0]?.replace(/^To:\s*/i, '').trim() || '';
  const subject = lines[1]?.replace(/^Subject:\s*/i, '').trim() || '';
  const body = lines
    .slice(3)
    .join('\n')
    .replace(/\n*(Regards|Best),\n.+\s*$/i, '')
    .trim();
  const closingMatch = value.match(/\n(Best|Regards),\n(.+)\s*$/i);
  const closing = closingMatch ? `${closingMatch[1]},\n${closingMatch[2]}` : '';
  const senderName = closingMatch ? closingMatch[2] : '';

  return { to, senderName, subject, body, closing, draft: value };
}

function buildGreetingFromRecipients(recipientField) {
  const names = String(recipientField || '')
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const local = entry.split('@')[0] || entry;
      return local
        .split(/[._-]/)
        .filter(Boolean)
        .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
        .join(' ');
    });

  if (names.length === 0) {
    return 'Hi Team,';
  }
  if (names.length === 1) {
    return `Hi ${names[0]},`;
  }
  return `Hi ${names.join(' and ')},`;
}

function startOfWeek(date) {
  const next = new Date(date);
  const day = next.getDay();
  const diff = day === 0 ? -6 : 1 - day;
  next.setDate(next.getDate() + diff);
  next.setHours(0, 0, 0, 0);
  return next;
}

function addDays(date, days) {
  const next = new Date(date);
  next.setDate(next.getDate() + days);
  return next;
}

function sameDay(left, right) {
  return left.getFullYear() === right.getFullYear()
    && left.getMonth() === right.getMonth()
    && left.getDate() === right.getDate();
}

function parseCalendarItemDate(item) {
  return new Date(`${item.date}T${item.time}`);
}

function formatWeekday(date) {
  return date.toLocaleDateString([], { weekday: 'short' });
}

function formatMonthDay(date) {
  return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

function formatWeekRange(startDate) {
  const endDate = addDays(startDate, 6);
  return `${startDate.toLocaleDateString([], { month: 'short', day: 'numeric' })} - ${endDate.toLocaleDateString([], { month: 'short', day: 'numeric' })}`;
}

function formatHourLabel(hour) {
  const suffix = hour >= 12 ? 'PM' : 'AM';
  const normalized = hour % 12 === 0 ? 12 : hour % 12;
  return `${normalized}:00 ${suffix}`;
}

function formatEventTime(time) {
  const [hourText, minuteText] = String(time || '').split(':');
  const hour = Number(hourText || 0);
  const minute = Number(minuteText || 0);
  const suffix = hour >= 12 ? 'PM' : 'AM';
  const normalized = hour % 12 === 0 ? 12 : hour % 12;
  return `${normalized}:${String(minute).padStart(2, '0')} ${suffix}`;
}

function calendarTone(itemType) {
  switch (itemType) {
    case 'TASK':
      return 'task';
    case 'REMINDER':
      return 'reminder';
    default:
      return 'meeting';
  }
}

function participantsText(participants) {
  return Array.isArray(participants) ? participants.join(', ') : '';
}

function buildCalendarForm(item) {
  return {
    title: item?.title || '',
    date: item?.date || '',
    time: String(item?.time || '').slice(0, 5),
    participants: participantsText(item?.participants),
    itemType: item?.itemType || 'MEETING',
    notes: item?.notes || ''
  };
}

function buildEmailForm(draft) {
  const scheduledFor = draft?.scheduledFor
    ? String(draft.scheduledFor).replace(' ', 'T').slice(0, 16)
    : '';
  return {
    recipient: draft?.recipient || '',
    senderName: draft?.senderName || '',
    subject: draft?.subject || '',
    body: draft?.body || '',
    tone: draft?.tone || 'professional',
    scheduledFor
  };
}

function EmailDraftCard({ draft, compact = false }) {
  const parsed = parseEmailDraft(draft);
  if (!parsed) {
    return <pre>{String(draft ?? '')}</pre>;
  }

  return (
    <div className={compact ? 'email-card compact' : 'email-card'}>
      <div className="email-row"><span>To</span><strong>{parsed.to}</strong></div>
      <div className="email-row"><span>From</span><strong>{parsed.senderName || '[Sender Name]'}</strong></div>
      <div className="email-row"><span>Subject</span><strong>{parsed.subject}</strong></div>
      <div className="email-body">
        <p>{parsed.body || 'No message body provided.'}</p>
        {parsed.closing && <p>{parsed.closing}</p>}
      </div>
    </div>
  );
}

function SendIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M3 20L21 12L3 4V10L15 12L3 14V20Z" fill="currentColor" />
    </svg>
  );
}

function StopIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" />
    </svg>
  );
}

function MenuIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="5" r="2" fill="currentColor" />
      <circle cx="12" cy="12" r="2" fill="currentColor" />
      <circle cx="12" cy="19" r="2" fill="currentColor" />
    </svg>
  );
}

function extractSessionIdentifiers(sessions) {
  const identifiers = [];

  for (const session of sessions) {
    for (const message of session.messages) {
      if (!message.result) {
        continue;
      }

      for (const trace of message.result.traces || []) {
        const parsed = tryParseJson(trace.outputSummary);

        if (trace.tool === 'MetadataLookupTool' && parsed?.uuid) {
          identifiers.push({
            id: `uuid-${parsed.uuid}-${message.id}`,
            kind: 'UUID',
            identifier: parsed.uuid,
            details: parsed.metadata || {},
            sourcePrompt: message.query,
            sessionName: session.name
          });
        }
      }
    }
  }

  return identifiers;
}

function mergeIdentifierLedger(sessionIdentifiers, calendarItems) {
  const ledger = [];
  const seen = new Set();

  for (const entry of sessionIdentifiers) {
    const key = `${entry.kind}:${entry.identifier}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    ledger.push(entry);
  }

  for (const item of calendarItems) {
    const key = `Event ID:${item.id}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    ledger.push({
      id: `event-${item.id}`,
      kind: 'Event ID',
      identifier: item.id,
      details: {
        title: item.title,
        date: item.date,
        time: item.time,
        participants: item.participants || [],
        status: item.status,
        itemType: item.itemType,
        notes: item.notes || ''
      },
      sourcePrompt: 'Persisted calendar item',
      sessionName: 'All chats'
    });
  }

  return ledger;
}

function extractEmailDraftIds(session) {
  const ids = [];
  for (const message of session.messages) {
    if (!message.result) {
      continue;
    }
    for (const trace of message.result.traces || []) {
      const parsed = tryParseJson(trace.outputSummary);
      const draftId = parsed?.id || parsed?.emailDraft?.id;
      if (draftId && !ids.includes(draftId)) {
        ids.push(draftId);
      }
    }
  }
  return ids;
}

function formatScore(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(3) : 'n/a';
}

function parseRagTrace(trace) {
  if (trace.tool !== 'RAGRetrievalTool') {
    return null;
  }
  return tryParseJson(trace.outputSummary);
}

function RagTraceInspector({ trace }) {
  const artifact = parseRagTrace(trace);
  if (!artifact) {
    return <pre>{String(trace.outputSummary ?? '')}</pre>;
  }

  const stages = [
    { label: 'Dense Hits', items: artifact.denseCandidates || [] },
    { label: 'Lexical Hits', items: artifact.lexicalCandidates || [] },
    { label: 'Fused Shortlist', items: artifact.fusedCandidates || [] },
    { label: 'Reranked Shortlist', items: artifact.rerankedCandidates || [] }
  ];

  return (
    <div className="rag-trace">
      <div className="rag-trace-summary">
        <strong>{artifact.answerContext?.summary || 'RAG retrieval artifact'}</strong>
        <span>
          {artifact.answerContext?.chunkCount || 0} chunk(s) · {artifact.answerContext?.characterCount || 0} chars
        </span>
      </div>

      {stages.map((stage) => (
        <div className="rag-stage" key={stage.label}>
          <p className="trace-label">{stage.label}</p>
          {stage.items.length === 0 ? (
            <p className="artifact-meta">No candidates.</p>
          ) : (
            <div className="rag-candidate-list">
              {stage.items.map((item) => (
                <article className="rag-candidate-card" key={`${stage.label}-${item.chunkId}`}>
                  <div className="identifier-head">
                    <strong>{item.sourceLabel}</strong>
                    <span className="artifact-tag">chunk {item.chunkIndex}</span>
                  </div>
                  <p className="artifact-meta">
                    dense {formatScore(item.denseScore)} · lexical {formatScore(item.lexicalScore)} · fused {formatScore(item.fusedScore)} · rerank {formatScore(item.rerankScore)} · final {formatScore(item.finalScore)}
                  </p>
                  <pre>{item.content}</pre>
                </article>
              ))}
            </div>
          )}
        </div>
      ))}

      <div className="rag-stage">
        <p className="trace-label">Final Context</p>
        {(artifact.contextChunks || []).length === 0 ? (
          <p className="artifact-meta">No context chunks selected.</p>
        ) : (
          <div className="rag-candidate-list">
            {(artifact.contextChunks || []).map((chunk) => (
              <article className="rag-candidate-card selected" key={`context-${chunk.chunkId}`}>
                <div className="identifier-head">
                  <strong>{chunk.sourceLabel}</strong>
                  <span className="artifact-tag">{chunk.inclusionReason}</span>
                </div>
                <p className="artifact-meta">chunk {chunk.chunkIndex} · final {formatScore(chunk.finalScore)}</p>
                <pre>{chunk.content}</pre>
              </article>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function App() {
  const [activePage, setActivePage] = useState('Agent Console');
  const [sessions, setSessions] = useState(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (!saved) {
      return [createSession('First Chat')];
    }
    try {
      return normalizeSessions(JSON.parse(saved));
    } catch {
      return [createSession('First Chat')];
    }
  });
  const [activeSessionId, setActiveSessionId] = useState(() => sessions[0].id);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [metadataQuery, setMetadataQuery] = useState('');
  const [calendarItems, setCalendarItems] = useState([]);
  const [emailDrafts, setEmailDrafts] = useState([]);
  const [selectedEmailDraftId, setSelectedEmailDraftId] = useState(null);
  const [emailForm, setEmailForm] = useState(buildEmailForm(null));
  const [emailEditorMessage, setEmailEditorMessage] = useState('');
  const [emailEditorBusy, setEmailEditorBusy] = useState(false);
  const [emailSearchQuery, setEmailSearchQuery] = useState('');
  const [selectedCalendarItemId, setSelectedCalendarItemId] = useState(null);
  const [calendarForm, setCalendarForm] = useState(buildCalendarForm(null));
  const [calendarEditorMessage, setCalendarEditorMessage] = useState('');
  const [calendarEditorBusy, setCalendarEditorBusy] = useState(false);
  const [contacts, setContacts] = useState([]);
  const [calendarAnchorDate, setCalendarAnchorDate] = useState(() => startOfWeek(new Date()));
  const [contactName, setContactName] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [contactMessage, setContactMessage] = useState('');
  const [sessionMenuId, setSessionMenuId] = useState(null);
  const [editingSessionId, setEditingSessionId] = useState(null);
  const [editingSessionName, setEditingSessionName] = useState('');
  const [stateNotice, setStateNotice] = useState('');
  const [systemState, setSystemState] = useState(null);
  const [modelSelectionBusy, setModelSelectionBusy] = useState(false);
  const [modelSelectionMessage, setModelSelectionMessage] = useState('');
  const [agentStatus, setAgentStatus] = useState({ tone: 'idle', text: 'Agent is ready.' });
  const abortRef = useRef(null);
  const timeoutRef = useRef(null);
  const abortReasonRef = useRef(null);
  const requestIdRef = useRef(0);
  const messageListRef = useRef(null);
  const menuRef = useRef(null);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions));
  }, [sessions]);

  const activeSession = useMemo(
    () => sessions.find((session) => session.id === activeSessionId) || sessions[0],
    [activeSessionId, sessions]
  );

  const identifierLedger = useMemo(
    () => mergeIdentifierLedger(extractSessionIdentifiers(sessions), calendarItems),
    [sessions, calendarItems]
  );
  const selectedCalendarItem = useMemo(
    () => calendarItems.find((item) => item.id === selectedCalendarItemId) || null,
    [calendarItems, selectedCalendarItemId]
  );
  const selectedEmailDraft = useMemo(
    () => emailDrafts.find((draft) => draft.id === selectedEmailDraftId) || emailDrafts[0] || null,
    [emailDrafts, selectedEmailDraftId]
  );
  const activeSessionDraftIds = useMemo(
    () => extractEmailDraftIds(activeSession),
    [activeSession]
  );

  useEffect(() => {
    reloadReferenceData();
  }, []);

  useEffect(() => {
    messageListRef.current?.scrollTo({ top: messageListRef.current.scrollHeight, behavior: 'smooth' });
  }, [activeSession?.messages?.length, loading]);

  useEffect(() => {
    if (!loading) {
      return undefined;
    }

    const slowTimer = window.setTimeout(() => {
      setAgentStatus({ tone: 'slow', text: 'Agent is still responding. The backend or model looks slow.' });
    }, 8000);

    const stuckTimer = window.setTimeout(() => {
      setAgentStatus({ tone: 'error', text: 'Agent may be stuck. Check the orchestrator and model logs or stop the run.' });
    }, 20000);

    return () => {
      window.clearTimeout(slowTimer);
      window.clearTimeout(stuckTimer);
    };
  }, [loading]);

  useEffect(() => {
    if (selectedCalendarItem) {
      setCalendarForm(buildCalendarForm(selectedCalendarItem));
    } else {
      setCalendarForm(buildCalendarForm(null));
    }
  }, [selectedCalendarItem]);

  useEffect(() => {
    if (selectedEmailDraft) {
      setEmailForm(buildEmailForm(selectedEmailDraft));
    } else {
      setEmailForm(buildEmailForm(null));
    }
  }, [selectedEmailDraft]);

  useEffect(() => {
    function handlePointerDown(event) {
      if (menuRef.current && !menuRef.current.contains(event.target)) {
        setSessionMenuId(null);
      }
    }

    document.addEventListener('mousedown', handlePointerDown);
    return () => document.removeEventListener('mousedown', handlePointerDown);
  }, []);

  async function reloadReferenceData() {
    try {
      const [draftsResponse, stateResponse, calendarResponse, contactsResponse] = await Promise.all([
        fetch(`${TOOLS_URL}/api/email/drafts`),
        fetch(`${TOOLS_URL}/api/state`),
        fetch(`${TOOLS_URL}/api/calendar/items`),
        fetch(`${TOOLS_URL}/api/contacts`)
      ]);
      const draftsBody = await draftsResponse.json();
      const stateBody = await stateResponse.json();
      const calendarBody = await calendarResponse.json();
      const contactsBody = await contactsResponse.json();
      const databaseId = stateBody.databaseId || '';
      const previousDatabaseId = localStorage.getItem(SYSTEM_STATE_KEY);

      if (previousDatabaseId && databaseId && previousDatabaseId !== databaseId) {
        const freshSessions = [createSession('First Chat')];
        setSessions(freshSessions);
        setActiveSessionId(freshSessions[0].id);
        localStorage.setItem(STORAGE_KEY, JSON.stringify(freshSessions));
        setStateNotice('Backend data was reset. Local chat history was cleared to keep calendar/tasks consistent.');
      }

      if (databaseId) {
        localStorage.setItem(SYSTEM_STATE_KEY, databaseId);
      }

      setSystemState(stateBody);
      setModelSelectionMessage('');
      setEmailDrafts(draftsBody.drafts || []);
      setCalendarItems(calendarBody.items || []);
      setContacts(contactsBody.contacts || []);
      if (selectedCalendarItemId && !(calendarBody.items || []).some((item) => item.id === selectedCalendarItemId)) {
        setSelectedCalendarItemId(null);
      }
      if (selectedEmailDraftId && !(draftsBody.drafts || []).some((draft) => draft.id === selectedEmailDraftId)) {
        setSelectedEmailDraftId((draftsBody.drafts || [])[0]?.id || null);
      } else if (!selectedEmailDraftId && (draftsBody.drafts || []).length > 0) {
        setSelectedEmailDraftId(draftsBody.drafts[0].id);
      }
    } catch {
      // Keep existing local state if refresh fails.
    }
  }

  async function changeGenerationModel(event) {
    const nextModel = event.target.value;
    if (!nextModel || nextModel === systemState?.activeGenerationModel) {
      return;
    }

    setModelSelectionBusy(true);
    setModelSelectionMessage('');
    try {
      const response = await fetch(`${TOOLS_URL}/api/state/model`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ generationModel: nextModel })
      });
      if (!response.ok) {
        throw new Error(`Model switch failed with HTTP ${response.status}`);
      }
      const stateBody = await response.json();
      setSystemState(stateBody);
      setModelSelectionMessage(`Model set to ${stateBody.activeGenerationModel}.`);
    } catch (error) {
      setModelSelectionMessage(error.message || 'Unable to switch models.');
    } finally {
      setModelSelectionBusy(false);
    }
  }

  async function runQuery() {
    const trimmed = query.trim();
    if (!trimmed || loading) {
      return;
    }

    const sessionBeforeSubmit = activeSession;
    const history = buildConversationHistory(sessionBeforeSubmit?.messages || []);
    const pendingClarification = latestPendingClarification(sessionBeforeSubmit?.messages || []);
    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;

    const submittedAt = new Date().toISOString();
    const pendingMessage = {
      id: crypto.randomUUID(),
      role: 'user',
      query: trimmed,
      createdAt: submittedAt
    };

    setQuery('');
    setLoading(true);
    setStateNotice('');
    setAgentStatus({ tone: 'loading', text: 'Sending request to the agent.' });
    abortReasonRef.current = null;
    setSessions((current) =>
      current.map((session) =>
        session.id === activeSessionId
          ? {
              ...session,
              name: session.messages.length === 0 ? deriveSessionName(trimmed) : session.name,
              updatedAt: submittedAt,
              messages: [...session.messages, pendingMessage]
            }
          : session
      )
    );

    const controller = new AbortController();
    abortRef.current = controller;
    timeoutRef.current = window.setTimeout(() => {
      abortReasonRef.current = 'timeout';
      controller.abort();
    }, AGENT_REQUEST_TIMEOUT_MS);

    try {
      const response = await fetch(`${ORCHESTRATOR_URL}/agent/query`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: trimmed, history, pendingClarification }),
        signal: controller.signal
      });
      if (!response.ok) {
        throw new Error(`Agent request failed with HTTP ${response.status}`);
      }
      const body = await response.json();
      if (requestIdRef.current !== requestId) {
        return;
      }

      setAgentStatus({ tone: 'success', text: 'Agent response received.' });

      setSessions((current) =>
        current.map((session) =>
          session.id === activeSessionId
            ? {
                ...session,
                updatedAt: new Date().toISOString(),
                messages: [
                  ...session.messages,
                  {
                    id: crypto.randomUUID(),
                    role: 'assistant',
                    query: trimmed,
                    result: body,
                    createdAt: new Date().toISOString()
                  }
                ]
              }
            : session
        )
      );
      await reloadReferenceData();
    } catch (error) {
      if (error.name === 'AbortError') {
        if (abortReasonRef.current === 'timeout') {
          setStateNotice('Agent request timed out.');
          setAgentStatus({ tone: 'error', text: 'Agent request timed out. Check the backend logs or try a smaller model profile.' });
          return;
        }
        setStateNotice('Request stopped.');
        setAgentStatus({ tone: 'stopped', text: 'Request stopped.' });
        return;
      }

      const result = { result: String(error), traces: [], iterations: 0, status: 'VALIDATION_ERROR' };
      setAgentStatus({ tone: 'error', text: `Agent failed: ${String(error)}` });

      setSessions((current) =>
        current.map((session) =>
          session.id === activeSessionId
            ? {
                ...session,
                updatedAt: new Date().toISOString(),
                messages: [
                  ...session.messages,
                  {
                    id: crypto.randomUUID(),
                    role: 'assistant',
                    query: trimmed,
                    result,
                    createdAt: new Date().toISOString()
                  }
                ]
              }
            : session
        )
      );
    } finally {
      if (requestIdRef.current === requestId) {
        abortRef.current = null;
        if (timeoutRef.current) {
          window.clearTimeout(timeoutRef.current);
          timeoutRef.current = null;
        }
        abortReasonRef.current = null;
        setLoading(false);
      }
    }
  }

  function stopRun() {
    requestIdRef.current += 1;
    if (timeoutRef.current) {
      window.clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    abortReasonRef.current = 'stopped';
    abortRef.current?.abort();
    abortRef.current = null;
    setLoading(false);
    setAgentStatus({ tone: 'stopped', text: 'Request stopped.' });
  }

  function createNewChat() {
    const next = createSession();
    setSessions((current) => [next, ...current]);
    setActiveSessionId(next.id);
    setActivePage('Agent Console');
    setQuery('');
    setSessionMenuId(null);
    setEditingSessionId(null);
  }

  function beginRenameSession(session) {
    setEditingSessionId(session.id);
    setEditingSessionName(session.name);
    setSessionMenuId(null);
  }

  function commitRenameSession(sessionId) {
    const trimmed = editingSessionName.trim();
    if (!trimmed) {
      setEditingSessionId(null);
      setEditingSessionName('');
      return;
    }
    setSessions((current) => current.map((session) => (
      session.id === sessionId
        ? { ...session, name: trimmed, updatedAt: new Date().toISOString() }
        : session
    )));
    setEditingSessionId(null);
    setEditingSessionName('');
  }

  function deleteSession(sessionId) {
    setSessions((current) => {
      const remaining = current.filter((session) => session.id !== sessionId);
      const nextSessions = normalizeSessions(remaining);
      if (!nextSessions.some((session) => session.id === activeSessionId)) {
        setActiveSessionId(nextSessions[0].id);
      }
      return nextSessions;
    });
    setSessionMenuId(null);
    setEditingSessionId(null);
    setEditingSessionName('');
  }

  async function saveContact(event) {
    event.preventDefault();
    setContactMessage('');
    try {
      const response = await fetch(`${TOOLS_URL}/api/contacts`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: contactName.trim(), email: contactEmail.trim() })
      });
      if (!response.ok) {
        throw new Error('Unable to save contact.');
      }
      setContactName('');
      setContactEmail('');
      setContactMessage('Contact saved.');
      await reloadReferenceData();
    } catch (error) {
      setContactMessage(String(error));
    }
  }

  async function removeContact(contactId) {
    setContactMessage('');
    try {
      const response = await fetch(`${TOOLS_URL}/api/contacts/${contactId}`, {
        method: 'DELETE'
      });
      if (!response.ok) {
        throw new Error('Unable to delete contact.');
      }
      setContactMessage('Contact deleted.');
      await reloadReferenceData();
    } catch (error) {
      setContactMessage(String(error));
    }
  }

  function openCalendarEditor(item) {
    setSelectedCalendarItemId(item.id);
    setCalendarEditorMessage('');
    setActivePage('Calendar Viewer');
  }

  function closeCalendarEditor() {
    setSelectedCalendarItemId(null);
    setCalendarEditorMessage('');
    setCalendarEditorBusy(false);
  }

  async function saveCalendarItem(event) {
    event.preventDefault();
    if (!selectedCalendarItem) {
      return;
    }
    setCalendarEditorBusy(true);
    setCalendarEditorMessage('');
    try {
      const participants = calendarForm.participants
        .split(',')
        .map((value) => value.trim())
        .filter(Boolean);

      const response = await fetch(`${TOOLS_URL}/api/calendar/items/${selectedCalendarItem.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: calendarForm.title.trim(),
          date: calendarForm.date,
          time: calendarForm.time,
          participants,
          itemType: calendarForm.itemType,
          notes: calendarForm.notes.trim()
        })
      });
      if (!response.ok) {
        throw new Error('Unable to save calendar item.');
      }
      await reloadReferenceData();
      setCalendarEditorMessage('Changes saved.');
    } catch (error) {
      setCalendarEditorMessage(String(error));
    } finally {
      setCalendarEditorBusy(false);
    }
  }

  async function removeCalendarItem() {
    if (!selectedCalendarItem) {
      return;
    }
    setCalendarEditorBusy(true);
    setCalendarEditorMessage('');
    try {
      const response = await fetch(`${TOOLS_URL}/api/calendar/items/${selectedCalendarItem.id}`, {
        method: 'DELETE'
      });
      if (!response.ok) {
        throw new Error('Unable to delete calendar item.');
      }
      await reloadReferenceData();
      closeCalendarEditor();
    } catch (error) {
      setCalendarEditorMessage(String(error));
      setCalendarEditorBusy(false);
    }
  }

  function selectEmailDraft(draft) {
    setSelectedEmailDraftId(draft.id);
    setEmailEditorMessage('');
  }

  async function saveEmailDraft(event) {
    event.preventDefault();
    if (!selectedEmailDraft) {
      return;
    }
    setEmailEditorBusy(true);
    setEmailEditorMessage('');
    try {
      const response = await fetch(`${TOOLS_URL}/api/email/drafts/${selectedEmailDraft.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          recipient: emailForm.recipient.trim(),
          senderName: emailForm.senderName.trim(),
          subject: emailForm.subject.trim(),
          body: emailForm.body.trim(),
          tone: emailForm.tone
        })
      });
      if (!response.ok) {
        throw new Error('Unable to save draft.');
      }
      await reloadReferenceData();
      setEmailEditorMessage('Draft saved.');
    } catch (error) {
      setEmailEditorMessage(String(error));
    } finally {
      setEmailEditorBusy(false);
    }
  }

  async function scheduleEmailDraft() {
    if (!selectedEmailDraft) {
      return;
    }
    setEmailEditorBusy(true);
    setEmailEditorMessage('');
    try {
      const response = await fetch(`${TOOLS_URL}/api/email/drafts/${selectedEmailDraft.id}/schedule`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ scheduledFor: emailForm.scheduledFor })
      });
      if (!response.ok) {
        throw new Error('Unable to schedule draft.');
      }
      await reloadReferenceData();
      setEmailEditorMessage('Draft scheduled.');
    } catch (error) {
      setEmailEditorMessage(String(error));
    } finally {
      setEmailEditorBusy(false);
    }
  }

  async function sendEmailDraft() {
    if (!selectedEmailDraft) {
      return;
    }
    setEmailEditorBusy(true);
    setEmailEditorMessage('');
    try {
      const response = await fetch(`${TOOLS_URL}/api/email/drafts/${selectedEmailDraft.id}/send`, {
        method: 'POST'
      });
      if (!response.ok) {
        throw new Error('Unable to mark draft as sent.');
      }
      await reloadReferenceData();
      setEmailEditorMessage('Draft marked as sent.');
    } catch (error) {
      setEmailEditorMessage(String(error));
    } finally {
      setEmailEditorBusy(false);
    }
  }

  async function deleteEmailDraft() {
    if (!selectedEmailDraft) {
      return;
    }
    setEmailEditorBusy(true);
    setEmailEditorMessage('');
    try {
      const response = await fetch(`${TOOLS_URL}/api/email/drafts/${selectedEmailDraft.id}`, {
        method: 'DELETE'
      });
      if (!response.ok) {
        throw new Error('Unable to delete draft.');
      }
      const deletedId = selectedEmailDraft.id;
      await reloadReferenceData();
      setSelectedEmailDraftId((current) => (current === deletedId ? null : current));
    } catch (error) {
      setEmailEditorMessage(String(error));
    } finally {
      setEmailEditorBusy(false);
    }
  }

  const filteredIdentifiers = identifierLedger.filter((entry) =>
    metadataQuery.trim() === '' ? true : entry.identifier.toLowerCase().includes(metadataQuery.trim().toLowerCase())
  );
  const filteredEmailDrafts = emailDrafts.filter((draft) => {
    const q = emailSearchQuery.trim().toLowerCase();
    if (!q) {
      return true;
    }
    return [draft.recipient, draft.subject, draft.body, draft.status]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(q));
  });
  const currentChatDrafts = filteredEmailDrafts.filter((draft) => activeSessionDraftIds.includes(draft.id));
  const draftOutbox = filteredEmailDrafts.filter((draft) => draft.status === 'DRAFT');
  const scheduledOutbox = filteredEmailDrafts.filter((draft) => draft.status === 'SCHEDULED');
  const sentOutbox = filteredEmailDrafts.filter((draft) => draft.status === 'SENT');
  const weekDays = useMemo(
    () => Array.from({ length: 7 }, (_, index) => addDays(calendarAnchorDate, index)),
    [calendarAnchorDate]
  );
  const weekCalendarItems = useMemo(
    () => calendarItems.filter((item) => weekDays.some((day) => sameDay(parseCalendarItemDate(item), day))),
    [calendarItems, weekDays]
  );
  const visibleHours = useMemo(() => {
    if (weekCalendarItems.length === 0) {
      return Array.from({ length: 14 }, (_, index) => index + 7);
    }
    const hours = weekCalendarItems.map((item) => parseCalendarItemDate(item).getHours());
    const minHour = Math.max(Math.min(...hours) - 1, 6);
    const maxHour = Math.min(Math.max(...hours) + 2, 22);
    return Array.from({ length: maxHour - minHour + 1 }, (_, index) => minHour + index);
  }, [weekCalendarItems]);

  return (
    <div className="app-scene">
      <div className="scene-gradient" />
      <div className="scene-orb orb-one" />
      <div className="scene-orb orb-two" />
      <div className="scene-grid" />
      <div className="app-shell">
        <aside className="sidebar" ref={menuRef}>
        <div className="sidebar-card brand-card">
          <p className="eyebrow">Local Agent Stack</p>
          <h1>MAB Embabel Local</h1>
          <p className="brand-copy">Threaded local agent console backed by real MCP tools and guarded routing.</p>
          <button className="new-chat" onClick={createNewChat}>New Chat</button>
        </div>

        <div className="sidebar-card">
          <p className="eyebrow">Sessions</p>
          <div className="session-list">
            {sessions.map((session) => {
              const sessionClasses = [
                'session-item',
                'session-shell',
                session.id === activeSession.id ? 'active' : '',
                sessionMenuId === session.id ? 'menu-open' : ''
              ].filter(Boolean).join(' ');

              return (
              <div
                key={session.id}
                className={sessionClasses}
              >
                <button className="session-main" onClick={() => setActiveSessionId(session.id)}>
                  {editingSessionId === session.id ? (
                    <input
                      className="session-rename"
                      value={editingSessionName}
                      autoFocus
                      onChange={(event) => setEditingSessionName(event.target.value)}
                      onBlur={() => commitRenameSession(session.id)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          commitRenameSession(session.id);
                        }
                        if (event.key === 'Escape') {
                          setEditingSessionId(null);
                          setEditingSessionName('');
                        }
                      }}
                    />
                  ) : (
                    <>
                      <strong>{session.name}</strong>
                      <span>{session.messages.length} messages</span>
                    </>
                  )}
                </button>
                <button
                  className="session-menu-trigger"
                  type="button"
                  aria-label="Session actions"
                  onClick={() => setSessionMenuId((current) => current === session.id ? null : session.id)}
                >
                  <MenuIcon />
                </button>
                {sessionMenuId === session.id && (
                  <div className="session-menu">
                    <button type="button" onClick={() => beginRenameSession(session)}>Rename</button>
                    <button type="button" className="danger" onClick={() => deleteSession(session.id)}>Delete</button>
                  </div>
                )}
              </div>
            )})}
          </div>
        </div>
        </aside>

        <main className="workspace">
        <nav className="tabs">
          {pages.map((page) => (
            <button
              key={page}
              className={activePage === page ? 'tab active' : 'tab'}
              onClick={() => setActivePage(page)}
            >
              {page}
            </button>
          ))}
        </nav>

        {activePage === 'Agent Console' && (
          <section className="panel chat-panel">
            <div className="panel-header">
              <div>
                <p className="eyebrow">Agent Console</p>
                <h2>{activeSession.name}</h2>
                {systemState?.generationModel && (
                  <p className="agent-model-meta">
                    Model: <strong>{systemState.activeGenerationModel || systemState.generationModel}</strong>
                    {systemState.modelAuthority ? ` via ${systemState.modelAuthority}` : ''}
                  </p>
                )}
                {!!systemState?.availableGenerationModels?.length && (
                  <label className="model-selector">
                    <span>Generation model</span>
                    <select
                      className="text-input"
                      value={systemState.activeGenerationModel || systemState.generationModel}
                      onChange={changeGenerationModel}
                      disabled={modelSelectionBusy}
                    >
                      {systemState.availableGenerationModels.map((model) => (
                        <option key={model} value={model}>{model}</option>
                      ))}
                    </select>
                  </label>
                )}
                {modelSelectionMessage && <p className="artifact-meta">{modelSelectionMessage}</p>}
              </div>
                <p className="panel-copy">Conversations persist locally in this browser. The main assistant surface is focused on email and calendar workflows.</p>
            </div>

            {stateNotice && <div className="state-notice">{stateNotice}</div>}
            <div className={`agent-status agent-status-${agentStatus.tone}`} aria-live="polite">
              <span className="agent-status-dot" aria-hidden="true" />
              <span>{agentStatus.text}</span>
            </div>

            <div className="message-list" ref={messageListRef}>
              {activeSession.messages.length === 0 && (
                <div className="empty-state">
                  <strong>No messages yet.</strong>
                  <p>Try: <code>add a reminder "push code to origin main" at 12pm today</code></p>
                  <p>Or: <code>draft an email to Joe about tomorrow&apos;s check-in</code></p>
                </div>
              )}

              {activeSession.messages.map((message) => (
                <article key={message.id} className={message.role === 'user' ? 'message user' : 'message assistant'}>
                  <div className="message-meta">
                    <strong>{message.role === 'user' ? 'You' : 'Agent'}</strong>
                    <span>{new Date(message.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
                  </div>

                  {message.role === 'user' ? (
                    <p className="message-text">{message.query}</p>
                  ) : (
                    <>
                      <div className={`message-outcome outcome-${messageOutcomeTone(message.result)}`}>
                        <strong>{messageOutcomeLabel(message.result)}</strong>
                        {message.result?.executionResult?.candidateSummaries?.length > 0 && (
                          <span>{message.result.executionResult.candidateSummaries.join(' | ')}</span>
                        )}
                      </div>
                      {parseEmailDraft(message.result?.result) ? (
                        <EmailDraftCard draft={message.result?.result} />
                      ) : (
                        <pre>{String(message.result?.result ?? '')}</pre>
                      )}
                      {message.result?.normalizedPlan && (
                        <details className="trace-item">
                          <summary className="trace-head">
                            <strong>Normalized Plan</strong>
                            <span>structured</span>
                          </summary>
                          <pre>{formatTracePayload(message.result.normalizedPlan)}</pre>
                        </details>
                      )}
                      {message.result?.executionResult && (
                        <details className="trace-item">
                          <summary className="trace-head">
                            <strong>Execution Result</strong>
                            <span>{message.result.executionResult.status}</span>
                          </summary>
                          <pre>{formatTracePayload(message.result.executionResult)}</pre>
                        </details>
                      )}
                      <div className="trace-list">
                        {(message.result?.traces || []).map((trace, index) => (
                          <details className="trace-item" key={`${message.id}-${trace.tool}-${index}`}>
                            <summary className="trace-head">
                              <strong>{trace.tool}</strong>
                              <span>{trace.success ? 'success' : 'failed'} in {trace.durationMs}ms</span>
                            </summary>
                            <p className="trace-label">Input</p>
                            <pre>{formatTracePayload(trace.inputSummary)}</pre>
                            <p className="trace-label">Output</p>
                            {trace.tool === 'EmailTool' ? (
                              <EmailDraftCard draft={trace.outputSummary} compact />
                            ) : trace.tool === 'RAGRetrievalTool' ? (
                              <RagTraceInspector trace={trace} />
                            ) : (
                              <pre>{formatTracePayload(trace.outputSummary ?? '')}</pre>
                            )}
                          </details>
                        ))}
                      </div>
                    </>
                  )}
                </article>
              ))}
            </div>

            <div className="composer">
              <label htmlFor="query">Prompt</label>
              <div className="composer-row">
                <textarea
                  id="query"
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && !event.shiftKey) {
                      event.preventDefault();
                      if (loading) {
                        stopRun();
                      } else {
                        runQuery();
                      }
                    }
                  }}
                  rows={3}
                  placeholder="Ask for one email or calendar task at a time."
                />
                <button
                  className={loading ? 'run icon-button stop' : 'run icon-button'}
                  onClick={loading ? stopRun : runQuery}
                  type="button"
                  aria-label={loading ? 'Stop request' : 'Send message'}
                >
                  {loading ? <StopIcon /> : <SendIcon />}
                </button>
              </div>
            </div>
          </section>
        )}

        {activePage === 'Calendar Viewer' && (
          <section className="panel">
            <div className="panel-header">
              <div>
                <p className="eyebrow">Calendar Viewer</p>
                <h2>Weekly Calendar</h2>
              </div>
              <p className="panel-copy">Meetings, tasks, and reminders laid out by day and timeslot, similar to a team calendar view.</p>
            </div>

            <div className="calendar-toolbar">
              <div>
                <strong>{formatWeekRange(calendarAnchorDate)}</strong>
                <p className="artifact-meta">Backend-backed calendar items for the selected week.</p>
              </div>
              <div className="calendar-nav">
                <button type="button" className="tab" onClick={() => setCalendarAnchorDate((current) => addDays(current, -7))}>Previous</button>
                <button type="button" className="tab" onClick={() => setCalendarAnchorDate(startOfWeek(new Date()))}>This Week</button>
                <button type="button" className="tab" onClick={() => setCalendarAnchorDate((current) => addDays(current, 7))}>Next</button>
              </div>
            </div>

            {calendarItems.length === 0 ? (
              <div className="empty-state"><strong>No calendar items yet.</strong><p>Create one from Agent Console first.</p></div>
            ) : (
              <div className="calendar-board">
                <div className="calendar-header time-axis" />
                {weekDays.map((day) => (
                  <div className="calendar-header" key={day.toISOString()}>
                    <strong>{formatWeekday(day)}</strong>
                    <span>{formatMonthDay(day)}</span>
                  </div>
                ))}

                {visibleHours.map((hour) => (
                  <Fragment key={`hour-${hour}`}>
                    <div className="time-axis">{formatHourLabel(hour)}</div>
                    {weekDays.map((day) => {
                      const slotItems = weekCalendarItems.filter((item) => {
                        const itemDate = parseCalendarItemDate(item);
                        return sameDay(itemDate, day) && itemDate.getHours() === hour;
                      });

                      return (
                        <div className="calendar-cell" key={`${day.toISOString()}-${hour}`}>
                          {slotItems.map((item) => (
                            <button
                              type="button"
                              className={`calendar-event ${calendarTone(item.itemType)}`}
                              key={item.id}
                              onClick={() => openCalendarEditor(item)}
                            >
                              <span className="calendar-event-time">{formatEventTime(item.time)}</span>
                              <strong>{item.title}</strong>
                              <span className="calendar-event-type">{item.itemType}</span>
                              {!!item.participants?.length && (
                                <span className="calendar-event-meta">{item.participants.join(', ')}</span>
                              )}
                            </button>
                          ))}
                        </div>
                      );
                    })}
                  </Fragment>
                ))}
              </div>
            )}

            {selectedCalendarItem && (
              <div className="calendar-editor-backdrop" onClick={closeCalendarEditor}>
                <section className="calendar-editor" onClick={(event) => event.stopPropagation()}>
                  <div className="panel-header compact-head">
                    <div>
                      <p className="eyebrow">Calendar Item</p>
                      <h2>Edit {selectedCalendarItem.itemType.toLowerCase()}</h2>
                    </div>
                    <button type="button" className="tab" onClick={closeCalendarEditor}>Close</button>
                  </div>

                  <form className="calendar-editor-form" onSubmit={saveCalendarItem}>
                    <label>
                      Title
                      <input
                        className="text-input"
                        value={calendarForm.title}
                        onChange={(event) => setCalendarForm((current) => ({ ...current, title: event.target.value }))}
                      />
                    </label>

                    <div className="calendar-editor-grid">
                      <label>
                        Date
                        <input
                          className="text-input"
                          type="date"
                          value={calendarForm.date}
                          onChange={(event) => setCalendarForm((current) => ({ ...current, date: event.target.value }))}
                        />
                      </label>

                      <label>
                        Time
                        <input
                          className="text-input"
                          type="time"
                          value={calendarForm.time}
                          onChange={(event) => setCalendarForm((current) => ({ ...current, time: event.target.value }))}
                        />
                      </label>
                    </div>

                    <div className="calendar-editor-grid">
                      <label>
                        Type
                        <select
                          className="text-input"
                          value={calendarForm.itemType}
                          onChange={(event) => setCalendarForm((current) => ({ ...current, itemType: event.target.value }))}
                        >
                          <option value="MEETING">Meeting</option>
                          <option value="TASK">Task</option>
                          <option value="REMINDER">Reminder</option>
                        </select>
                      </label>

                      <label>
                        Participants
                        <input
                          className="text-input"
                          value={calendarForm.participants}
                          onChange={(event) => setCalendarForm((current) => ({ ...current, participants: event.target.value }))}
                          placeholder="alex@example.local, sam@example.local"
                        />
                      </label>
                    </div>

                    <label>
                      Notes
                      <textarea
                        value={calendarForm.notes}
                        onChange={(event) => setCalendarForm((current) => ({ ...current, notes: event.target.value }))}
                        rows={4}
                      />
                    </label>

                    <p className="artifact-meta">Event ID: {selectedCalendarItem.id}</p>
                    {calendarEditorMessage && <p className="artifact-meta">{calendarEditorMessage}</p>}

                    <div className="calendar-editor-actions">
                      <button type="submit" className="run" disabled={calendarEditorBusy}>
                        {calendarEditorBusy ? 'Saving...' : 'Save Changes'}
                      </button>
                      <button type="button" className="tab danger-tab" disabled={calendarEditorBusy} onClick={removeCalendarItem}>
                        Delete
                      </button>
                    </div>
                  </form>
                </section>
              </div>
            )}
          </section>
        )}

        {activePage === 'Email Mockup' && (
          <section className="panel split-panel">
            <div>
              <div className="panel-header compact-head">
                <div>
                  <p className="eyebrow">Email Mockup</p>
                  <h2>Draft Library</h2>
                </div>
                <p className="panel-copy">A standalone email workspace. It surfaces current-chat drafts first, then lets you manage all drafts, scheduled sends, and sent items.</p>
              </div>

              <label>
                Search Emails
                <input
                  className="text-input"
                  value={emailSearchQuery}
                  onChange={(event) => setEmailSearchQuery(event.target.value)}
                  placeholder="Search by recipient, subject, body, or status"
                />
              </label>

              {selectedEmailDraft && (
                <form className="artifact-card wide email-editor-card" onSubmit={saveEmailDraft}>
                  <div className="identifier-head">
                    <strong>Edit Draft</strong>
                    <span className="artifact-tag">{selectedEmailDraft.status}</span>
                  </div>

                  <label>
                    To
                    <input
                      className="text-input"
                      value={emailForm.recipient}
                      onChange={(event) => setEmailForm((current) => ({ ...current, recipient: event.target.value }))}
                    />
                  </label>

                  <label>
                    Sender
                    <input
                      className="text-input"
                      value={emailForm.senderName}
                      onChange={(event) => setEmailForm((current) => ({ ...current, senderName: event.target.value }))}
                    />
                  </label>

                  <label>
                    Subject
                    <input
                      className="text-input"
                      value={emailForm.subject}
                      onChange={(event) => setEmailForm((current) => ({ ...current, subject: event.target.value }))}
                    />
                  </label>

                  <div className="calendar-editor-grid">
                    <label>
                      Tone
                      <select
                        className="text-input"
                        value={emailForm.tone}
                        onChange={(event) => setEmailForm((current) => ({ ...current, tone: event.target.value }))}
                      >
                        <option value="professional">Professional</option>
                        <option value="casual">Casual</option>
                        <option value="urgent">Urgent</option>
                      </select>
                    </label>

                    <label>
                      Schedule Send
                      <input
                        className="text-input"
                        type="datetime-local"
                        value={emailForm.scheduledFor}
                        onChange={(event) => setEmailForm((current) => ({ ...current, scheduledFor: event.target.value }))}
                      />
                    </label>
                  </div>

                  <label>
                    Body
                    <textarea
                      rows={8}
                      value={emailForm.body}
                      onChange={(event) => setEmailForm((current) => ({ ...current, body: event.target.value }))}
                    />
                  </label>

                  <div className="email-editor-actions">
                    <button className="run" type="submit" disabled={emailEditorBusy}>
                      {emailEditorBusy ? 'Working...' : 'Save Draft'}
                    </button>
                    <button className="tab" type="button" disabled={emailEditorBusy || !emailForm.scheduledFor} onClick={scheduleEmailDraft}>
                      Schedule
                    </button>
                    <button className="tab" type="button" disabled={emailEditorBusy} onClick={sendEmailDraft}>
                      Send
                    </button>
                    <button className="tab danger-tab" type="button" disabled={emailEditorBusy} onClick={deleteEmailDraft}>
                      Delete
                    </button>
                  </div>

                  <p className="artifact-meta">Draft ID: {selectedEmailDraft.id}</p>
                  {selectedEmailDraft.sentAt && <p className="artifact-meta">Sent at: {selectedEmailDraft.sentAt}</p>}
                  {selectedEmailDraft.scheduledFor && <p className="artifact-meta">Scheduled for: {selectedEmailDraft.scheduledFor}</p>}
                  {emailEditorMessage && <p className="artifact-meta">{emailEditorMessage}</p>}
                </form>
              )}

              {emailDrafts.length === 0 && <div className="empty-state"><strong>No drafts yet.</strong><p>Ask the agent to generate an email to populate this view.</p></div>}

              {currentChatDrafts.length > 0 && (
                <section className="email-section">
                  <div className="panel-header compact-head">
                    <div>
                      <p className="eyebrow">Current Chat</p>
                      <h2>Relevant Drafts</h2>
                    </div>
                  </div>
                  <div className="artifact-grid">
                    {currentChatDrafts.map((draft) => (
                      <article
                        className={selectedEmailDraft?.id === draft.id ? 'artifact-card wide selectable-card active' : 'artifact-card wide selectable-card'}
                        key={`chat-${draft.id}`}
                        onClick={() => selectEmailDraft(draft)}
                      >
                        <div className="identifier-head">
                          <strong>{draft.subject}</strong>
                          <span className="artifact-tag">{draft.status}</span>
                        </div>
                        <p className="artifact-meta">{draft.recipient}</p>
                        <EmailDraftCard draft={draft} />
                      </article>
                    ))}
                  </div>
                </section>
              )}

              <section className="email-section">
                <div className="panel-header compact-head">
                  <div>
                    <p className="eyebrow">Outbox</p>
                    <h2>Drafts</h2>
                  </div>
                </div>
                <div className="artifact-grid">
                  {draftOutbox.length === 0 && <div className="empty-state"><strong>No unsent drafts.</strong><p>Drafts awaiting schedule or send appear here.</p></div>}
                  {draftOutbox.map((draft) => (
                    <article
                      className={selectedEmailDraft?.id === draft.id ? 'artifact-card wide selectable-card active' : 'artifact-card wide selectable-card'}
                      key={`draft-${draft.id}`}
                      onClick={() => selectEmailDraft(draft)}
                    >
                      <div className="identifier-head">
                        <strong>{draft.subject}</strong>
                        <span className="artifact-tag">{draft.status}</span>
                      </div>
                      <p className="artifact-meta">{draft.recipient}</p>
                      <EmailDraftCard draft={draft} />
                    </article>
                  ))}
                </div>
              </section>

              <section className="email-section">
                <div className="panel-header compact-head">
                  <div>
                    <p className="eyebrow">Outbox</p>
                    <h2>Scheduled Sends</h2>
                  </div>
                </div>
                <div className="artifact-grid">
                  {scheduledOutbox.length === 0 && <div className="empty-state"><strong>No scheduled emails.</strong><p>Emails scheduled to send later appear here.</p></div>}
                  {scheduledOutbox.map((draft) => (
                    <article
                      className={selectedEmailDraft?.id === draft.id ? 'artifact-card wide selectable-card active' : 'artifact-card wide selectable-card'}
                      key={`scheduled-${draft.id}`}
                      onClick={() => selectEmailDraft(draft)}
                    >
                      <div className="identifier-head">
                        <strong>{draft.subject}</strong>
                        <span className="artifact-tag">{draft.status}</span>
                      </div>
                      <p className="artifact-meta">{draft.recipient}</p>
                      {draft.scheduledFor && <p className="artifact-meta">Scheduled for: {draft.scheduledFor}</p>}
                      <EmailDraftCard draft={draft} />
                    </article>
                  ))}
                </div>
              </section>

              <section className="email-section">
                <div className="panel-header compact-head">
                  <div>
                    <p className="eyebrow">Outbox</p>
                    <h2>Sent</h2>
                  </div>
                </div>
                <div className="artifact-grid">
                  {sentOutbox.length === 0 && <div className="empty-state"><strong>No sent emails.</strong><p>Placeholder-sent emails are tracked here.</p></div>}
                  {sentOutbox.map((draft) => (
                    <article
                      className={selectedEmailDraft?.id === draft.id ? 'artifact-card wide selectable-card active' : 'artifact-card wide selectable-card'}
                      key={`sent-${draft.id}`}
                      onClick={() => selectEmailDraft(draft)}
                    >
                      <div className="identifier-head">
                        <strong>{draft.subject}</strong>
                        <span className="artifact-tag">{draft.status}</span>
                      </div>
                      <p className="artifact-meta">{draft.recipient}</p>
                      {draft.sentAt && <p className="artifact-meta">Sent at: {draft.sentAt}</p>}
                      <EmailDraftCard draft={draft} />
                    </article>
                  ))}
                </div>
              </section>
            </div>

              <div>
                <div className="panel-header compact-head contact-panel-header">
                  <div className="contact-panel-title">
                    <p className="eyebrow">People Directory</p>
                    <h2>Recipients</h2>
                  </div>
                  <p className="panel-copy">Manage saved people and email addresses used for recipient resolution.</p>
                </div>
              <form className="contact-form" onSubmit={saveContact}>
                <input
                  className="text-input"
                  value={contactName}
                  onChange={(event) => setContactName(event.target.value)}
                  placeholder="Full name"
                />
                <input
                  className="text-input"
                  value={contactEmail}
                  onChange={(event) => setContactEmail(event.target.value)}
                  placeholder="email@example.local"
                  type="email"
                />
                <button className="run secondary" type="submit">Save Contact</button>
                {contactMessage && <p className="artifact-meta">{contactMessage}</p>}
              </form>
              <div className="contact-list">
                {contacts.map((contact) => (
                  <article className="artifact-card" key={contact.id}>
                    <strong>{contact.name}</strong>
                    <p>{contact.email}</p>
                    <button className="tab danger-tab contact-delete" type="button" onClick={() => removeContact(contact.id)}>
                      Delete Contact
                    </button>
                  </article>
                ))}
              </div>
            </div>
          </section>
        )}

        {activePage === 'Metadata Inspector' && (
          <section className="panel">
            <div className="panel-header">
              <div>
                <p className="eyebrow">Metadata Inspector</p>
                <h2>Identifier Ledger</h2>
              </div>
                <p className="panel-copy">Tracked identifiers across chats and persisted calendar items, including event IDs and UUIDs. Known issue: standalone/global lookup behavior is still inconsistent in some cases.</p>
            </div>

            <label htmlFor="metadata-query">Filter by identifier</label>
            <input
              id="metadata-query"
              className="text-input"
              value={metadataQuery}
              onChange={(event) => setMetadataQuery(event.target.value)}
              placeholder="Enter an event ID or UUID"
            />

            <div className="artifact-grid">
              {filteredIdentifiers.length === 0 && <div className="empty-state"><strong>No tracked identifiers yet.</strong><p>Schedule a calendar item or request metadata by UUID in Agent Console first.</p></div>}
              {filteredIdentifiers.map((entry) => (
                <article className="artifact-card wide" key={`${entry.id}-${entry.sourcePrompt}`}>
                  <div className="identifier-head">
                    <strong>{entry.identifier}</strong>
                    <span className="artifact-tag">{entry.kind}</span>
                  </div>
                  <p className="artifact-meta">Source: {entry.sourcePrompt} · Session: {entry.sessionName}</p>
                  <pre>{JSON.stringify(entry.details, null, 2)}</pre>
                </article>
              ))}
            </div>
          </section>
        )}
        </main>
      </div>
    </div>
  );
}

export default App;
