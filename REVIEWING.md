# Reviewing PRs

Reviewing PRs for Canvas is honestly pretty simple, but can be time-consuming
depending on the PR in question. Overall, we expect people to review the diff
thoroughly, and also `checkout` the PR in question and actually apply patches
if applicable and view the **full** diff in its entirety, since sometimes we
may add code that is a rewrite of other code(or based on other code), and the
raw diff from the PR won't actually show the original.

We also expect people to test the changes in question. Assume we add a new
command or something, reviewers should always try and test locally the new
feature to make sure it all works properly and consistently. If you did test,
feel free to comment saying you tested. However, that does not mean if
someone has already commented saying they tested, that means you shouldnt.
The more testing that happens, the better, since that means we can ensure
top quality code is entering our master branch.

## Provenance and license review

Reviewers must treat [`PROVENANCE.md`](PROVENANCE.md) as a merge gate. For every
non-trivial patch or direct source edit:

1. Classify it as inherited, derived/ported, or independent.
2. Open every immutable upstream link and verify the commit, path, original
   author, and license.
3. Compare the applied Canvas source with the cited source, not only the PR
   diff or patch email header.
4. Confirm source comments and copyright notices were preserved. A manual
   reimplementation or patch rebuild does not justify changing them to
   `// Canvas`.
5. Verify that adaptation notes explain semantic changes, configuration
   mapping, and Folia thread-ownership changes.
6. For an independent implementation that resembles existing work, examine
   the dated issue, crash report, design notes, and tests. Similarity plus weak
   evidence requires provenance clarification before merge.
7. Confirm all required license notices ship in
   `canvas-server/src/main/resources/META-INF/licenses/`.

Missing, vague, or disputed provenance blocks merge and release even when the
code is small, appears correct, or can be fixed later. Ask for clarification in
the public PR record; private discussion may supplement but must not replace the
auditable record.

## For Maintainers

If you are wanting to merge it, ask the other maintainers first to see if
anyone is waiting on something before it can be merged(like say, waiting for
another person to review or to test later on). Also, always ask the author
of the PR if it's ready to be merged too, since they might want to make
further changes, or they noticed something they want to fix for example. Just
generally, communicate before merging.
