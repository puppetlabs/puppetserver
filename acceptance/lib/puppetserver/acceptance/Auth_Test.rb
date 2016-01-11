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
      when 'Range'
          if @expected_result.include?(@actual_result.to_i)
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
      return true if @rule['allow'].include?(:host) #if the rule says allow hostname, it should be authorized.      
    end
    return false
  end


  def calculate_expected_result
    if method_match(@method, @rule['match-request']['method']) &&
           authorized? then
      @expected_result = (200..299)
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

end
