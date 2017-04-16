class hiera_test {
  $foo=hiera('val', 'not set')
  notify { "hiera value=$foo": }
}
