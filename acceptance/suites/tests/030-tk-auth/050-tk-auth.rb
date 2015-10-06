require 'puppetserver/acceptance/Auth_Test'
require 'hocon/config_factory'

confine :except, :platform => 'windows'

def generate_tests(ruleset)
  array_of_tests = Array.new
  methods = ['GET', 'PUT', 'POST', 'DELETE', 'HEAD', 'OPTIONS', 'TRACE', 'CONNECT']
  ruleset.each do |r|
    methods.each do |m|
      #TODO:
      #testhost.each in [master, agent, 'invalidTestHost.delivery.puppetlabs.net']
      #replace #{master} below with test host.
      #expand the expected_result_calc in Auth_Test to look at url_cn

      case r['match-request']['path']
      when '/puppet/v3/status'
        path = '/puppet/v3/status/dummyValue?environment=production'
      when '^/puppet/v3/node/([^/]+)$'
        path = "/puppet/v3/node/#{master}?environment=production"
      when '^/puppet/v3/catalog/([^/]+)$'
        path = "/puppet/v3/catalog/#{master}?environment=production"
      when '^/puppet/v3/report/([^/]+)$'
        #TODO: Handle report behavior
        # path = "/puppet/v3/report/#{master}?environment=production"
        next  #Skipping over it for now.
      when '/puppet-ca/v1/certificate_request'
        #TODO: considering building a full certificate request
        #so that we can change our expected_result to a 200
        path = '/puppet-ca/v1/certificate_request'
        if m = 'PUT' || 'GET' then
          expected_result = '404'
        end
      when '/puppet-ca/v1/certificate/'
        #TODO: In order to make the GET case for this end point work,
        #we need to provide a valid node name and environment.
        #/puppet-ca/v1/certificate/:nodename?environment=:environment
        if m == 'GET' then
          expected_result = '404'
        end
        path = '/puppet-ca/v1/certificate/' 
      when /\^/

        path = r['match-request']['path'].match('[a-zA-Z0-9\/]+')[0]
      else
        path = r['match-request']['path']
      end


      array_of_tests << Auth_Test.new(  :host             => master, 
                                        :method           => m,
                                        :path             => path,
                                        :cn               => master,
                                        :rule             => r, 
                                        :expected_result  => expected_result)
    end
  end
  return array_of_tests
end


test_name "TK Auth Deep Test" do
  
    step 'Generate and execute tests for each rule in auth.conf...' do
      authconf_text = on(master, 'cat /etc/puppetlabs/puppetserver/conf.d/auth.conf').stdout
      authconf_hash = Hocon.parse(authconf_text)
      tests=generate_tests(authconf_hash['authorization']['rules'])

      tests.each do |t|
        #TODO: discuss if the entire curlstring should come from the class or not...
        w = "-w \"\\nhttpresponse: %{http_code}\\n\" --max-time 2 -s --show-error --include"
        curlstr = "curl #{w} #{t.auth_string} -X #{t.method} #{t.url}"
        t.actual_result_detail = on(master, curlstr, :acceptable_exit_codes => [-1, 0, 1, 7, 28])
        t.set_porf
      end

      tests.each do |t|
        assert_match("PASS",t.porf,'FAILED on #{t.method} #{t.path}\nEXPECTED: #{t.expected_result}\nACTUAL: #{t.actual_result}\n')
        t.show_detail if t.porf == 'FAIL'
      end

    end


end

