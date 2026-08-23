# gh / GraphQL Command Reference

All the `gh` and GraphQL commands the workflow needs, in one place. Copy and substitute the placeholders.

Placeholders used throughout:
- `<owner>` / `<repo>` — repository owner and name
- `<pr>` — PR number (integer)
- `<thread-id>` — review thread node ID (a `PRRT_…`-style global ID, returned in `reviewThreads.nodes[].id`)
- `<comment-id>` — the *databaseId* of the comment being replied to (an integer, not the global ID)

## Identifying the PR

If the user gives you a URL like `https://github.com/acme/widgets/pull/482`, parse `<owner>=acme`, `<repo>=widgets`, `<pr>=482`. If they give you just a PR number, you can usually default to the current repo:

```bash
gh repo view --json owner,name -q '"\(.owner.login)/\(.name)"'
```

## List unresolved review threads

```bash
gh api graphql -f query='
  query($owner: String!, $repo: String!, $pr: Int!) {
    repository(owner: $owner, name: $repo) {
      pullRequest(number: $pr) {
        reviewThreads(first: 100) {
          nodes {
            id
            isResolved
            isOutdated
            comments(first: 50) {
              nodes {
                id
                databaseId
                author { login }
                body
                path
                line
                originalLine
                diffHunk
                createdAt
              }
            }
          }
        }
      }
    }
  }
' -F owner=<owner> -F repo=<repo> -F pr=<pr>
```

Then filter to `isResolved == false`. The first comment in each thread is the original review comment; later comments are replies.

## Read the file at the comment location

```bash
# Get the live file from the PR's head branch.
gh pr view <pr> --json headRefName -q .headRefName
# Then check out or fetch:
git fetch origin <head-branch>
git show origin/<head-branch>:<path>
```

If you're already on the PR branch locally, just read the file directly.

## Reply on a thread

There are two equivalent ways. The REST one is shorter:

```bash
gh api repos/<owner>/<repo>/pulls/<pr>/comments \
  -f body="<reply text>" \
  -F in_reply_to=<comment-id>
```

`<comment-id>` here is the `databaseId` of the *first* comment in the thread (the integer, not the GraphQL node ID). Using the wrong ID gives a 422.

GraphQL equivalent (rare; use only if you also need the node ID back for further mutations):

```bash
gh api graphql -f query='
  mutation($pr: ID!, $body: String!, $replyTo: ID!) {
    addPullRequestReviewThreadReply(input: {
      pullRequestReviewThreadId: $replyTo,
      body: $body
    }) { comment { id } }
  }
' -F pr=<pr-node-id> -F body="<reply>" -F replyTo=<thread-id>
```

## Resolve a thread

Only use after replying, and only when the team norm allows the author to resolve. See the Gotchas in SKILL.md.

```bash
gh api graphql -f query='
  mutation($threadId: ID!) {
    resolveReviewThread(input: {threadId: $threadId}) {
      thread { isResolved }
    }
  }
' -F threadId=<thread-id>
```

To unresolve (useful if you resolved by mistake):

```bash
gh api graphql -f query='
  mutation($threadId: ID!) {
    unresolveReviewThread(input: {threadId: $threadId}) {
      thread { isResolved }
    }
  }
' -F threadId=<thread-id>
```

## Common error responses

- **404 on `/pulls/<pr>/comments`** — PR number wrong, or the repo path is wrong, or `gh` is authenticated to a different account that can't see the repo. Run `gh auth status`.
- **422 on the reply** — almost always the wrong `in_reply_to` ID. Make sure it's the integer `databaseId` of the *first* comment in the thread, not the global node ID and not the thread ID.
- **403 on resolve** — you don't have write access. On forks, you need to be repo collaborator; being the PR author is not enough.

## Without `gh`

If `gh` isn't installed, the same calls work via `curl` against the REST or GraphQL endpoints with a `GITHUB_TOKEN`. But the simpler fallback is to ask the user to paste the comment list and reply via the GitHub web UI; the decision-making part of the workflow is the valuable bit, the API calls are mechanical.
