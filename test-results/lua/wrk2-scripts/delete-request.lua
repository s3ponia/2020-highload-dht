function RandomVariable(length)
  local res = ""
  for i = 1, length do
    res = res .. string.format("%02x", math.random(255))
  end
  return res
end


request = function()
  math.randomseed(os.time())
  path = "/v0/entity?id=" .. RandomVariable(512)
  wrk.method = "DELETE"
  return wrk.format(nil, path)
end
