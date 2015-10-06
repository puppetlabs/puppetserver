require 'hocon/config_factory'

confine :except, :platform => 'windows'


#TODO: in the near future this class ought to be split out to a lib.
class Auth_Test
    @@total_fails=0
    @@total_passes=0
  attr_accessor :on_host, #this is the host on which we run the curl
    :host, :port, :method, :path, :url_cn, #these are the things we need to build the url
    :url, # build from the above attributes 
    :cn, #commonName in the certificate, used to find cert files
    :hostcert_file, :key_file, :cacert_file, #requires cn be set above.
    :auth_string, #curl string composed of from above three attribs 
    :rule, #rule under test
    :expected_result, 
    :actual_result,
    :actual_result_detail, #TODO: maybe make this a hash...
    :porf

  def initialize args
    args.each do |k,v|
      instance_variable_set("@#{k}", v) unless v.nil?
    end
    set_defaults
    calculate_expected_result unless @expected_result
  end


  def set_defaults
      @port           ||= '8140'      
      @method         ||= 'GET'
      @cacert_file    ||= "/etc/puppetlabs/puppet/ssl/ca/ca_crt.pem"
      @key_file       ||= "/etc/puppetlabs/puppet/ssl/private_keys/#{@cn}.pem"
      @hostcert_file  ||= "/etc/puppetlabs/puppet/ssl/certs/#{@cn}.pem"
      @auth_string    ||= "--cacert #{@cacert_file} --key #{@key_file} --cert #{@hostcert_file}" 
      @url            ||= "https://" + @host + ':'+ @port + @path
      @url            += @url_cn unless @url_cn.nil?
  end


  def set_porf
    @actual_result ||= @actual_result_detail.stdout.match(/httpresponse:.*/)[0].match(/[0-9]../)[0]
    case @expected_result.class.to_s
      when 'Array'
        if @expected_result.include?(@actual_result) then
          @porf = 'PASS'
          @@total_passes += 1
        else
          @porf = 'FAIL'
          @@total_fails += 1
        end
      when 'String'
          if @expected_result == @actual_result then 
            @porf = 'PASS' 
          else @porf = 'FAIL'
          @@total_fails += 1
          end
      else 
        puts 'How did we get here?'
        @porf = 'FAIL'
    end

    puts @porf
  end


  def method_match (method_to_test, ruleset_method)
    # return true if the method in this test matches the method in the selected ruleset
    ruleset_method = 'GET' if ruleset_method == nil 
    ruleset_method.include?(method_to_test.downcase)
  end


  def authorized? 
    # returns true if the rule authorizes the request
    #TODO: accept a host name and calculate if the rule authorizes the host
    #we have to handle the case where path contains ([^/]+ and allow => $1 in here.
    #in this case, we need to seperate 
    # 1.  the path we are going to test 
    # 2.  the path in the rule under test
    # So,
    # If cn=hostBob is looking for /puppet/v3/report/hostBob Than we AUTH = True
    # If cn=SomeOtherHost is looking for /puppet/v3/report/hostBob Than AUTH = FALSE.
    return true if @rule.keys.include?('allow-unauthenticated') && 
      @rule['allow-unauthenticated'] # (-k and everything else will work)

    if @rule['allow'] != nil then
      return true if @rule['allow'].include?('*') #TODO: express that -k won't work
      return true if @rule['allow'].include?('$1') #TODO and its a regex and the cn is signed and the cn is in the url regex thing
    end
    return false
  end


  def calculate_expected_result
    if method_match(@method, @rule['match-request']['method']) &&
           authorized? then
      @expected_result = '200'
    else
      @expected_result = Array['400','403','404']
    end
  end

  def show_detail
    puts '-' * 80
    puts @porf
    printf "%-20s %s\n", "Expected Result: ", @expected_result
    printf "%-20s %s\n", "Actual Result: ", @actual_result
    printf "%-20s %s\n", "Method Tested: ", @method
    printf "%-20s %s\n", "Path Tested: ", @path
    printf "%-20s %s\n", "Rule (Under Test): ", @rule
    pp @actual_result_detail 
  end

  def self.total_fails
    @@total_fails
  end

  def self.total_passes
    @@total_passes
  end

end #ends the class.


def generate_tests(ruleset)
  array_of_tests = Array.new
  methods = ['GET', 'PUT', 'POST', 'DELETE', 'HEAD', 'OPTIONS', 'TRACE', 'CONNECT']
  ruleset.each do |r|
    methods.each do |m|

      case r['match-request']['path']
      when '/puppet/v3/status'
        path = '/puppet/v3/status/dummyValue?environment=production'
      when '^/puppet/v3/node/([^/]+)$'
        #TODO: Get clarity about where to put the special cases.
        #Currently Auth_Test contains a function that strips regex 
        #from the path.
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

