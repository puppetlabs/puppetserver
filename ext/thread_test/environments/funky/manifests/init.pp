notify {'hello': }

notify { threader::doit(): }

notify { oldschool(): }

include threader::params_class
