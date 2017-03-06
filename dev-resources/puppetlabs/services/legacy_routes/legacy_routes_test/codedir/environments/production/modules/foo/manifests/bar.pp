class foo::bar inherits foo {
   include foo::baz
   notify { "Foo::Bar": }
   $digest = digest("Foo::Bar")
   notify { "Foo Digest":
     message => $digest,
   }
}