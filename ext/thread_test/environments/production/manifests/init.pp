node default {
  notify { 'hello': }

  notify { threader::doit(): }

  notify { threader::color(): }

  notify { oldschool(): }

  include threader::params_class
}

node 'testthree' {
  notify { threader::other_func(): }
}
