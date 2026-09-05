# RC4 readback boundary: source repair is not phone acceptance

## Reproduction before behavioral changes

- Runtime base: `d9441d057501bd49b6d66b0f653c96ffa0bf859c`; handoff head
  `3928688977e9587434d368c3070a9890feaf942c` adds documentation only.
- `b507127fbf64a90b64f15f1e99766167cd44bfea` introduced an explicit engine dependency
  seam, tests and an unsigned-evidence build switch. Its first run failed to compile
  the test HTTP peer (`com.sun.net.httpserver` is not on the Android compile path).
  This compilation failure is NOT evidence of the application's defect.
- `04d21709839f545a7173bf9b7ae6145e31bed94b` corrected only that test peer to standard
  sockets, retaining the old engine behavior.
- Baseline CI: https://github.com/onedayonemasterpiece/record-idea-hub/actions/runs/33965946090
- Downloaded verification artifact: `9969457353`, SHA256
  `a2636c8999f38b8f31afe063e940a19b1c0309fd9c823af81274b2a6417b13ad`.
- Built PR merge source: `e68498b6f19386e6e8a9f88ba1b651e1f3cf4ecf`.
- Parsed JUnit XML: all 26 pre-existing tests passed; new boundary suite: 8 tests,
  7 failures, 0 errors. It uses real SyncEngine, HttpURLConnection, Android JSON
  and Robolectric SDK 35 SQLite, including database close/reopen.

The failures demonstrate POST-create instead of GET after accepted complete;
`complete_sent`/upload-receipt loss on a late `chunks_missing` or regressed server
receipt; and indistinguishable generic messages for injected malformed JSON and
an injected SQLite write abort. The reconciliation/404 test already passes.

**Importantly, the old happy-path test reaches persisted terminal proofs and
local purge before failing its final GET-only assertion. A valid status fixture
does not reproduce the unexplained phone exception.** Neither the injected JSON
exception nor the injected SQLite exception is evidence of the phone's root cause.

## Targeted source changes

Accepted-complete recovery reads only GET status. It cannot reset upload flags,
re-create, re-upload or resend complete. Contradictory replies require safe
reconciliation. Existing quota delays remain non-bypassable by the user retry.
HTTP/JSON/persistence failures retain bounded code metadata in the existing
`last_error` field and safe logcat output: stage, exception class, HTTP status
when observed, an allowlisted error code and own-code frames. No exception
message/cause, raw body, token, settings or audio is logged. Successful receipt
handling still requires all purge proofs; reconciliation blocks contradictory
terminal proofs. Two additional tests cover quota and contradictory flags.

There is no database migration, capture/VAD change, server deployment, new
inference request or change to the user's phone in this source package.
The final run/artifact identifiers and read-back results belong in the PR #5
checkpoint comment; do not infer success from this document alone.

## Unresolved acceptance

The actual caught Samsung exception is still unidentified from the available
export. No phone restoration or new control recording has been performed by this
ChatGPT task. Matching RC4 signing-key availability has not been established;
unsigned CI APKs are NOT update candidates. Do not declare PRODUCT PASS.

A bounded no-install observer and exact local handoff are saved in
`tools/readback/` and `docs/handoffs/20260905-rc4-exception-only.md`. The observer
was exercised on a synthetic local JVM, not Android hardware. It is a diagnostic
unblock, not a completed repair or authorization to regenerate this session.
