help:
  @just --list

# Runs tests suite
test *KAOCHA_ARGS:
  ./bin/kaocha {{KAOCHA_ARGS}}

# Runs tests directly through midje in case kaocha has problems
midje:
  clojure -Srepro -A:test -M -e "(require 'midje.repl) (-> (midje.repl/load-facts) :failures (min 255) (System/exit))"

midje-watch:
  clojure -Srepro -A:test -M -e "(require 'midje.repl) (midje.repl/autotest)"

# Lints dependencies so static analysis can be better
lint-deps:
  @echo "Linting deps, will take a while..."
  clj-kondo --lint "$(clojure -Srepro -Spath -A:test)" > /dev/null || true

# Copies over clj-kondo configs from dependencies
lint-copy-configs:
  clj-kondo --copy-configs --dependencies --lint "$(clojure -Srepro -Spath -A:test)"

# Lints clojure source code, assumes lint-deps has been run
lint:
  clj-kondo --lint src:test

# Prints clojure dependency tree
tree *TREE_ARGS:
  clojure -Srepro -Stree {{TREE_ARGS}}
