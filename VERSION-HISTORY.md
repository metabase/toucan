# Toucan Version History & Release Notes

### [Unreleased]

* Make `toucan.db/insert!` and friends use HoneySQL, so that you can use `honeysql.core/call` for SQL function calls. ([@plexus](https://github.com/plexus))

### [1.0.3] (May 3rd, 2017)

*  Fixed [#6](https://github.com/metabase/toucan/issues/6): Fix hydration for fields that end in `-id`. ([@plexus](https://github.com/plexus))

### 1.0.2 (Jan 27th, 2017)

*  Minor documentation tweaks.

### 1.0.1 (Jan 27th, 2017)

*  Make `toucan.db/simple-insert!` public.
*  Add default implementation for `WithTempDefaults`.
*  Minor code reörganization.


### 1.0.0 (Jan 26th, 2017)

*  Initial release of Toucan.

[Unreleased]: https://github.com/metabase/toucan/compare/1.0.3...HEAD
[1.0.3]: https://github.com/metabase/toucan/compare/1.0.2...1.0.3
