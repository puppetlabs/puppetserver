notify { 'hello': }

notify { threader::doit(): }

notify { threader::futurist(): }

notify { oldschool(): }

include threader::params_class
