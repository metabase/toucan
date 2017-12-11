Thanks for contributing to Toucan. Before open a pull request, please take a moment to:

- [ ] Ensure the PR follows the [Clojure Style Guide](https://github.com/bbatsov/clojure-style-guide) and the [Metabase Clojure Style Guide](https://github.com/metabase/metabase/wiki/Metabase-Clojure-Style-Guide).
- [ ] Tests and linters pass. You can run them locally as follows:

      lein test && lein lint

    (CircleCI will also run these same tests against your PR.)
- [ ] Make sure you've included new tests for any new features or bugfixes
- [ ] New features are documented, or documentation is updated appropriately for any changed features.
- [ ] Carefully review your own changes and revert any superfluous ones. (A good example would be moving words in the Markdown documentation to different lines in a way that wouldn't change how the rendered page itself would appear. These sorts of changes make a PR bigger than it needs to be, and, thus, harder to review.)

    Of course, indentation and typo fixes are not covered by this rule and are always appreciated.
- [ ] Include a detailed explanation of what changes you're making and why you've made them. This will help us understand what's going on while we review it.

Once you've done all that, open a PR! Make sure to at-mention @camsaul in the PR description. Otherwise I won't get an email about it and might not get review it right away. :)

Thanks for your contribution!
