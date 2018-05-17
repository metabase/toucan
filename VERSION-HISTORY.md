# Toucan Version History & Release Notes

### [1.1.6.1](https://github.com/metabase/toucan/compare/1.1.5...1.1.6.1) (May 16th, 2018)

*  Add new [`toucan.hydrate/flush-hydration-key-caches!`](https://github.com/metabase/toucan/blob/master/docs/hydration.md#flushing-the-hydration-key-caches-for-interactive-repl-development)
   function for interactive development. (PRs [#27](https://github.com/metabase/toucan/pull/27) and [#35](https://github.com/metabase/toucan/pull/35), Credit: [@axrs](https://github.com/axrs) with some cleanup by [@camsaul](https://github.com/camsaul))
*  Fix how `select` and related functions generate `WHERE` clauses when used with clauses with more than two arguments, such as `:between`. ([#33](https://github.com/metabase/toucan/issues/33); Credit: [@camsaul](https://github.com/camsaul))

### [1.1.5](https://github.com/metabase/toucan/compare/1.1.4...1.1.5) (May 15th, 2018)

*  Add support for newer versions of H2 database.

### [1.1.4](https://github.com/metabase/toucan/compare/1.1.3...1.1.4) (January 17th, 2018)

*  Add new [`select-reducible` function](https://github.com/metabase/toucan/blob/master/docs/db-functions.md#select-reducible) to fetch streams of results
   from the database. (PR [#30](https://github.com/metabase/toucan/pull/30); Credit: [@senior](https://github.com/senior)).

### [1.1.3](https://github.com/metabase/toucan/compare/1.1.2...1.1.3) (Decmember 29th, 2017)

*  Add option to automatically convert dashed names to underscores in queries, and underscores in result rows keys to dashes in query results.
   See documentation [here](https://github.com/metabase/toucan/blob/master/docs/setup.md#automatically-converting-dashes-and-underscores).
   (PRs [#26](https://github.com/metabase/toucan/issues/26), [#28](https://github.com/metabase/toucan/issues/28), and [#29](https://github.com/metabase/toucan/issues/29);
   Credits: [@AndreTheHunter](https://github.com/AndreTheHunter) with some cleanup by [@camsaul](https://github.com/camsaul))

### [1.1.2](https://github.com/metabase/toucan/compare/1.1.1...1.1.2) (December 18th, 2017)

*  Add optional new `post-update` handler. (PR [#23](https://github.com/metabase/toucan/issues/23), Credit: [@axrs](https://github.com/axrs))

### [1.1.1](https://github.com/metabase/toucan/compare/1.1.0...1.1.1) (December 11th, 2017)

*  `update!` now works correctly with non-integer IDs. ([#20](https://github.com/metabase/toucan/issues/20)) (Credit: [@AndreTheHunter](https://github.com/AndreTheHunter))
*  Fix issue where `defmodel` macro didn't work correctly inside of other macros ([#19](https://github.com/metabase/toucan/issues/19)) (Credit: [@AndreTheHunter](https://github.com/AndreTheHunter))
*  Improvements to make tests & linters easier to run locally ([#19](https://github.com/metabase/toucan/issues/19), [#21](https://github.com/metabase/toucan/issues/21)) (Credits: [@camsaul](https://github.com/camsaul), [@AndreTheHunter](https://github.com/AndreTheHunter))

### [1.1.0](https://github.com/metabase/toucan/compare/1.0.3...1.1.0) (June 21st, 2017)

*  Make `toucan.db/insert!` and friends use HoneySQL, so that you can use `honeysql.core/call` for SQL function calls. (Credit: [@plexus](https://github.com/plexus))
*  You can now pass protocol implementations directly to `defmodel`, the same way as you would with `defrecord` or `deftype`
   ([#9](https://github.com/metabase/toucan/issues/9)) (Credit: [@plexus](https://github.com/plexus))
*  Toucan models now implement `empty` ([#1](https://github.com/metabase/toucan/issues/1)) and `apply` ([#2](https://github.com/metabase/toucan/issues/2)) (Credit: [@plexus](https://github.com/plexus))
*  Fix typo in error message ([#8](https://github.com/metabase/toucan/issues/8)) (Credit: [@bandresen](https://github.com/bandresen))


### [1.0.3](https://github.com/metabase/toucan/compare/1.0.2...1.0.3) (May 3rd, 2017)

*  Fixed [#6](https://github.com/metabase/toucan/issues/6): Fix hydration for fields that end in `-id`. (Credit: [@plexus](https://github.com/plexus))


### 1.0.2 (Jan 27th, 2017)

*  Minor documentation tweaks.


### 1.0.1 (Jan 27th, 2017)

*  Make `toucan.db/simple-insert!` public.
*  Add default implementation for `WithTempDefaults`.
*  Minor code reörganization.


### 1.0.0 (Jan 26th, 2017)

*  Initial release of Toucan.
