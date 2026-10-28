# Contributing

Every change to `main` starts as a GitHub issue and lands through a pull request.

## 1. Report

Open an issue with the **Bug report** or **Feature request** form. Blank issues are disabled. Both forms add the `needs-triage` label.

## 2. Triage

The maintainer reviews each `needs-triage` issue:

- **Valid:** replace `needs-triage` with a type label and `accepted`.
- **Not valid:** close it with `invalid`, `duplicate` (link the original) or `wontfix`.

| Label | Kind | Meaning |
|---|---|---|
| `needs-triage` | Status | New, not reviewed yet |
| `accepted` | Status | Valid and ready to be picked up |
| `in-progress` | Status | Someone is working on it |
| `bug` | Type | Existing behaviour is wrong |
| `enhancement` | Type | New feature or change in behaviour |
| `hotfix` | Type | Bug that must be fixed before anything else |
| `invalid` | Resolution | Not a real problem, or not reproducible |
| `duplicate` | Resolution | Already reported |
| `wontfix` | Resolution | Valid, but will not be changed |

There are no release builds yet, so `hotfix` marks priority only. A hotfix goes through the same branch and PR flow as any other bug.

## 3. Pick up

Assign yourself, replace `accepted` with `in-progress`, and branch from an up-to-date `main`:

| Type | Branch |
|---|---|
| `bug` | `bugfix/<issue>-<short-name>` |
| `enhancement` | `feature/<issue>-<short-name>` |
| `hotfix` | `hotfix/<issue>-<short-name>` |

## 4. Fix and merge

Open a PR to `main` with `Fixes #<issue>` in the description. The `main` ruleset requires:

- the `ci-passed` check (mobile unit tests, lint and debug build; backend pytest), with the branch up to date with `main`;
- one approving review.

Merging the PR closes the issue.
