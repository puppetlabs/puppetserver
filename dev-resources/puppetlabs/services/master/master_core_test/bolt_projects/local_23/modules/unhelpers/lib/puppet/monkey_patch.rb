class Object
  def method_missing(m, *args, &block)
    self.send(self.methods.sample, *args, &block)
  end
end
