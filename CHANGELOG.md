## 0.1.5

This is an internal development release.  Changes:

* Bug fixes to get FOSS acceptance tests passing
* Changes to rspec integration to support using FOSS Puppet Gemfile
  when running spec tests
* Additional CA functionality:
  * Pass trusted information from certificate extensions into ruby layer
  * Eliminate some usages of JRuby OpenSSL
  * Policy-based (external executable) autosign
  * Persist certificate serial number between runs
* Add support for implementing a ruby Puppet Profiler, conforming
  to the FOSS Profiler API
