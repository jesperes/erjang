
/**
 * This file is part of Erjang - A JVM-based Erlang VM
 *
 * Copyright (c) 2009 by Trifork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package erjang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import kilim.Pausable;
import erjang.m.java.JavaObject;

/**
 * An erlang process
 */
public final class EProc extends ETask<EInternalPID> {
	
	static Logger log = Logger.getLogger("erjang.proc");

    /*==================== Constants =============================*/

    public static final EObject TAIL_MARKER = null;
	
	public static final EAtom am_trap_exit = EAtom.intern("trap_exit");
	public static final EAtom am_sensitive = EAtom.intern("sensitive");
	public static final EAtom am_messages = EAtom.intern("messages");
	public static final EAtom am_message_queue_len = EAtom.intern("message_queue_len");
	public static final EAtom am_dictionary = EAtom.intern("dictionary");
	public static final EAtom am_group_leader = EAtom.intern("group_leader");
	public static final EAtom am_links = EAtom.intern("links");
	public static final EAtom am_heap_size = EAtom.intern("heap_size");
	public static final EAtom am_stack_size = EAtom.intern("stack_size");
	public static final EAtom am_reductions = EAtom.intern("reductions");
	public static final EAtom am_initial_call = EAtom.intern("initial_call");
	public static final EAtom am_current_function = EAtom.intern("current_function");
	public static final EAtom am_priority = EAtom.intern("priority");
	public static final EAtom am_memory = EAtom.intern("memory");
	public static final EAtom am_monitor_nodes = EAtom.intern("monitor_nodes");
	public static final EAtom am_registered_name = EAtom.intern("registered_name");
	public static final EAtom am_error_handler = EAtom.intern("error_handler");
	public static final EAtom am_undefined_function = EAtom.intern("undefined_function");

	public static final EAtom am_max = EAtom.intern("max");
	public static final EAtom am_normal = EAtom.intern("normal");
	public static final EAtom am_low = EAtom.intern("low");
	public static final EAtom am_high = EAtom.intern("high");

	static final EObject am_kill = EAtom.intern("kill");
	static final EObject am_killed = EAtom.intern("killed");

	private static final EObject am_status = EAtom.intern("status");
	private static final EObject am_waiting = EAtom.intern("waiting");
	private static final EObject am_running = EAtom.intern("running");
	private static final EObject am_runnable = EAtom.intern("runnable");

	private static final EAtom am_error_logger = EAtom.intern("error_logger");
	private static final EAtom am_info_report = EAtom.intern("info_report");

	private static final EObject am_noproc = EAtom.intern("noproc");

    private static final EAtom[] priorities = new EAtom[] {
            am_max,
            am_high,
            am_normal,
            am_low
    };

    private static final ExitHook[] NO_HOOKS = new ExitHook[0];


    /*==================== Global state ====================================*/
    public static ConcurrentHashMap<Integer,EProc> all_tasks
            = new ConcurrentHashMap<Integer,EProc> ();

    /*==================== Process state ====================================*/
    /*========= Immutable state =========================*/
    private final EInternalPID self;

    // TODO: Make final (and set in constructor rather than in setTarget)
    private EAtom spawn_mod;
    private EAtom spawn_fun;
    private int spawn_args;

    /*========= Mutable Erlang-dictated state ===========*/
    private EPID group_leader;
    public ErlangException last_exception; // TODO: Make private
    private final Map<EObject, EObject> pdict = new HashMap<EObject, EObject>();
    private EAtom trap_exit = ERT.FALSE;
    private EAtom sensitive = ERT.FALSE;
    private EAtom error_handler = am_error_handler;

    private int priority = 2;

    /*========= Mutable implementation-related state ====*/

    // TODO - make private. (Only accessed from ERT.) Add accessors/operations.
    // Message receive group:
    /** Message box index - used for selective receive */
    public int midx = 0;
    public long timeout_start;
    public boolean in_receive;

    /** Used for implementing tail calls. */
    public EFun tail;
    public EObject arg0, arg1, arg2, arg3, arg4, arg5,
    arg6, arg7, arg8, arg9, arg10, arg11,
    arg12, arg13, arg14, arg15, arg16, arg17;

	// For interpreter use:
	public EObject[] stack = new EObject[10];
	public int sp = 0;
    public EObject[] regs;
    public EDouble[] fregs = new EDouble[16];

    public EModuleManager.FunctionInfo undefined_function
            = EModuleManager.get_module_info(error_handler).
            get_function_info(new FunID(error_handler, am_undefined_function, 3));

    /** For process clean-up. Protected by exit-action mutator lock. */
    private final List<ExitHook> exit_hooks = new ArrayList<ExitHook>();
    ERT.TraceFlags trace_flags;


    /*==================== Construction =============================*/

	public EProc(EPID group_leader, EAtom m, EAtom f, Object[] a) {
		self = new EInternalPID(this);

		// if no group leader is given, we're our own group leader
		this.group_leader = group_leader == null ? self : group_leader;
		
		setTarget(m, f, a);
		
		all_tasks.put(key(), this);
	}

	/**
	 * @param m
	 * @param f
	 * @param array
	 */
	public EProc(EPID group_leader, EAtom m, EAtom f, ESeq a) {
		self = new EInternalPID(this);

		// if no group leader is given, we're our own group leader
		this.group_leader = group_leader == null ? self : group_leader;
		
		setTarget(m, f, a);
		
		all_tasks.put(key(), this);
	}
	
	protected void setTarget(EAtom m, EAtom f, Object[] args) {
		// wrap any non-EObject argument in JavaObject
		EObject[] eargs = new EObject[args.length];
		for (int i = 0; i < args.length; i++) {
			Object arg = args[i];
			if (arg instanceof EObject) {
				EObject earg = (EObject) arg;
				eargs[i] = earg;
			}
			else {
				// wrap in JavaObject
				eargs[i] = JavaObject.box(this, arg);
			}
		}
		setTarget(m, f, ESeq.fromArray(eargs));
	}
	
	protected void setTarget(EAtom m, EAtom f, ESeq a) {
		this.spawn_mod = m;
		this.spawn_fun = f;
		this.spawn_args = a.length();
		
		int arity = spawn_args;
		EFun target = EModuleManager.resolve(new FunID(m,f,arity));
		
		if (target == null) {
			throw new ErlangUndefined(m, f, new ESmall(arity));
		}
		
		this.tail = target;
		a = a.reverse();
		switch (arity) {
		default:
			throw new NotImplemented();
		case 7: 
			this.arg6 = a.head(); a = a.tail();
		case 6: 
			this.arg5 = a.head(); a = a.tail();
		case 5: 
			this.arg4 = a.head(); a = a.tail();
		case 4: 
			this.arg3 = a.head(); a = a.tail();
		case 3: 
			this.arg2 = a.head(); a = a.tail();
		case 2: 
			this.arg1 = a.head(); a = a.tail();
		case 1: 
			this.arg0 = a.head(); a = a.tail();
		case 0:
		}
	}

    /*==================== Public interface - runtime ===============*/
    public EInternalPID self_handle() {
        return self;
    }

    public ErlangException getLastException() {
        return last_exception;
    }

    /*==================== Public interface - Erlang operations =====*/

    /**
     * @return
     */
    public EPID group_leader() {
        return group_leader;
    }

    /**
     * Only called from ELocalPID
     *
     * @param group_leader
     */
    void set_group_leader(EPID group_leader) {
        this.group_leader = group_leader;
    }

    /*--------- Process dictionary --------------------------*/
    public EObject put(EObject key, EObject value) {
        EObject res = pdict.put(key, value);
        if (res == null)
            return ERT.am_undefined;
        return res;
    }

    public EObject get(EObject key) {
        EObject res = pdict.get(key);
        return (res == null) ? ERT.am_undefined : res;
    }

    /**
     * @return list of the process dictionary
     */
    public ESeq get() {
        ESeq res = ERT.NIL;
        for (Map.Entry<EObject, EObject> ent : pdict.entrySet()) {
            res = res.cons(ETuple.make(ent.getKey(), ent.getValue()));
        }
        return res;
    }

    /**
     * @param key
     * @return
     */
    public EObject erase(EObject key) {
        EObject res = pdict.remove(key);
        if (res == null)
            res = ERT.am_undefined;
        return res;
    }

    /**
     * @return
     */
    public EObject erase() {
        EObject res = get();
        pdict.clear();
        return res;
    }

    /*--------- Process flags --------------------------*/
    /**
     * @param testAtom
     * @param a2
     * @return
     * @throws Pausable
     */
    public EObject process_flag(EAtom flag, EObject value) {

        if (flag == am_trap_exit) {
            EAtom old = this.trap_exit;
            trap_exit = value.testBoolean();
            return ERT.box(old==ERT.TRUE);
        }

        if (flag == am_priority) {
            EAtom old = priorities[getPriority()];
            for (int i = 0; i < priorities.length; i++) {
                if (value == priorities[i]) {
                    setPriority(i);
                    return old;
                }
            }
            throw ERT.badarg(flag, value);
        }

        if (flag == am_error_handler) {
            EAtom val;
            if ((val = value.testAtom()) != null) {
                EAtom old = this.error_handler;
                this.error_handler = val;

                FunID uf = new FunID(error_handler, am_undefined_function, 3);
                undefined_function = EModuleManager.get_module_info(error_handler).get_function_info(uf);

                return old;
            } else {
                throw ERT.badarg(flag,  value);
            }
        }

        if (flag == am_monitor_nodes) {
            if (!value.isBoolean()) throw ERT.badarg(flag, value);
            boolean activate = value==ERT.TRUE;
            Boolean old = EAbstractNode.monitor_nodes(self_handle(), activate, ERT.NIL);
            if (old == null) throw ERT.badarg(flag, value);
            return ERT.box(old.booleanValue());
        }

        if (flag == am_trap_exit) {
            EAtom old = this.trap_exit;
            trap_exit = value.testBoolean();
            return ERT.box(old==ERT.TRUE);
        }

        if (flag == am_sensitive) {
            EAtom old = this.sensitive;
            sensitive = value.testBoolean();
            return ERT.box(old==ERT.TRUE);
        }

        ETuple2 tup;
        if ((tup = ETuple2.cast(flag)) != null && tup.elem1==am_monitor_nodes) {
            ESeq opts = tup.elem2.testSeq();
            if (opts == null) throw ERT.badarg(flag, value);

            if (!value.isBoolean()) throw ERT.badarg(flag, value);
            boolean activate = value.testBoolean()==ERT.TRUE;

            Boolean old = EAbstractNode.monitor_nodes(self_handle(), activate, opts);
            if (old == null) throw ERT.badarg(flag, value);
            return ERT.box(old.booleanValue());
        }

        throw new NotImplemented("process_flag flag="+flag+", value="+value);
    }

    private int getPriority() {
        return this.priority;
    }

    private void setPriority(int i) {
        this.priority  = i;
    }


    /*--------- Process info --------------------------*/

    public EObject process_info() {

        ESeq res = ERT.NIL;

        res = res.cons(process_info(am_trap_exit));
        res = res.cons(process_info(am_messages));
        res = res.cons(process_info(am_message_queue_len));
        res = res.cons(process_info(am_dictionary));
        res = res.cons(process_info(am_group_leader));
        res = res.cons(process_info(am_links));
        res = res.cons(process_info(am_heap_size));
        res = res.cons(process_info(am_initial_call));
        res = res.cons(process_info(am_reductions));

        EObject reg_name = self_handle().name;
        if (reg_name != ERT.am_undefined)
            res = res.cons(new ETuple2(am_registered_name, reg_name));

        if (res == ERT.NIL) return ERT.am_undefined;
        return res;
    }

    /**
     * @param spec
     * @return
     */
    public EObject process_info(EObject spec) {
        if (spec == am_registered_name) {
            return self_handle().name == ERT.am_undefined
                    ? ERT.NIL
                    : new ETuple2(am_registered_name, self_handle().name);
        } else if (spec == am_trap_exit) {
            return new ETuple2(am_trap_exit, trap_exit);
        } else if (spec == am_message_queue_len) {
            return new ETuple2(am_message_queue_len,
                    new ESmall(mbox.size()));
        } else if (spec == am_messages) {
            ESeq messages = EList.make((Object[])mbox.messages());
            return new ETuple2(am_messages, messages);
        } else if (spec == am_dictionary) {
            return new ETuple2(am_dictionary, get());
        } else if (spec == am_group_leader) {
            return new ETuple2(am_group_leader, group_leader);
        } else if (spec == am_links) {
            ESeq links = links();
            return new ETuple2(am_links, links);
        } else if (spec == am_status) {
            if (this.running.get()) {
                return new ETuple2(am_status, am_running);
            } else if (this.pauseReason != null) {
                return new ETuple2(am_status, am_waiting);
            } else {
                return new ETuple2(am_status, am_runnable);
            }
        } else if (spec == am_heap_size) {
            return new ETuple2(am_heap_size,
                    ERT.box(Runtime.getRuntime().totalMemory()
                            - Runtime.getRuntime().freeMemory()));
        } else if (spec == am_stack_size) {
            // TODO: Maybe use HotSpotDiagnosticMXBean ThreadStackSize property?
            return new ETuple2(am_stack_size,
                    ERT.box(0));

        } else if (spec == am_reductions) {
            return new ETuple2(am_reductions, ERT.box(this.get_reductions()));

        } else if (spec == am_initial_call) {
            return new ETuple2(am_initial_call,
                    ETuple.make(spawn_mod, spawn_fun, ERT.box(spawn_args)));

        } else if (spec == am_current_function) {
            /** TODO: fix this so we return something meaningful... */
            return new ETuple2(am_current_function,
                    ETuple.make(spawn_mod, spawn_fun, ERT.box(spawn_args)));

        } else if (spec == am_memory) {
            return new ETuple2(am_memory, ERT.box(50000));

        } else if (spec == am_error_handler) {
            return new ETuple2(am_error_handler, am_error_handler);

        } else if (spec == am_priority) {
            return new ETuple2(am_priority, am_normal);

        } else {
            log.warning("NotImplemented: process_info("+spec+")");
            throw new NotImplemented("process_info("+spec+")");
        }
    }

    /*==================== Internals ================================*/

    private int key() {
		int key = (self.serial() << 15) | (self.id() & 0x7fff);
		return key;
	}


    /*--------- Process lifecycle --------------------------*/

    public boolean is_alive_dirtyread() {
        int pstate = get_state_dirtyread();
        return pstate == STATE.INIT.ordinal() || pstate == STATE.RUNNING.ordinal();
    }


    protected void link_failure(EHandle h) throws Pausable {
		if (trap_exit == ERT.TRUE || h.testLocalHandle()==null) {
			send_exit(h, am_noproc, false);
		} else {
			throw new ErlangError(am_noproc);
		}
	}

	@Override
	protected void do_proc_termination(EObject result) throws Pausable {
        // Precondition: pstate is DONE, exit-action mutator count is zero.
		final ExitHook[] hooks;
        if (exit_hooks == null || exit_hooks.isEmpty()) {
            hooks = NO_HOOKS;
        } else {
            hooks = exit_hooks.toArray(new ExitHook[exit_hooks.size()]);
        }

		for (int i = 0; i < hooks.length; i++) {
			hooks[i].on_exit(self);
		}
		
		super.do_proc_termination(result);

		self.done();
		
		all_tasks.remove(this.key());
		
		
	}
	
	protected void process_incoming_exit(EHandle from, EObject reason, boolean is_erlang_exit2) throws Pausable
	{
        int pstate = get_state_dirtyread();
        if (exit_reason != null || pstate == STATE.DONE.ordinal()) {
            if (log.isLoggable(Level.FINE)) {
				log.fine("Ignoring incoming exit reason="+reason+", as we're already exiting reason="+exit_reason);
			}
            return;
		}
		
		if (log.isLoggable(Level.FINE)) {
			log.log(Level.FINE, "incoming exit to "+this+" from "+from+" reason="+reason+"; is_exit2="+is_erlang_exit2);
		}
		
		if (is_erlang_exit2) {
            EObject exit_reason_to_set;
			if (reason == am_kill) {
				exit_reason_to_set = am_killed;
			} else if (trap_exit == ERT.TRUE) {
				// we're trapping exits, so we in stead send an {'EXIT', from,
				// reason} to self
				ETuple msg = ETuple.make(ERT.am_EXIT, from, reason);
				// System.err.println("kill message to self: "+msg);
				
				mbox.put(msg);
				return;
				
			} else {
				exit_reason_to_set = reason;
			}

			this.exit_reason = exit_reason_to_set;
			this.killer = new Throwable("stack of process calling exit");
			this.resume();
			return;
		}
		
		if (from == self_handle()) {
			return;
			
		} 

		if (trap_exit == ERT.TRUE) {
			// we're trapping exits, so we in stead send an {'EXIT', from,
			// reason} to self
			ETuple msg = ETuple.make(ERT.am_EXIT, from, reason);
			// System.err.println("kill message to self: "+msg);
			
			mbox.put(msg);
			
		} else if (reason == am_kill) {
			this.exit_reason = am_killed;
			this.killer = new Throwable();
			this.resume();

		} else if (reason != am_normal) {
			// System.err.println("kill signal: " +reason + " from "+from);
			// try to kill this thread
			this.exit_reason = reason;
			this.killer = new Throwable();
			this.resume();
		}
	}


	@Override
	public void execute() throws Pausable {
		Throwable[] death = new Throwable[1];
		EObject result = null;
		try {

			execute0(death, result);

		} catch (ErlangHalt e) {
                        return;

		} catch (ThreadDeath e) {
			throw e;
			
		} catch (Throwable e) {
			System.err.println("uncaught top-level exception");
			e.printStackTrace(System.err);
		}
	}

	private void execute0(Throwable[] death, EObject result) throws ErlangHalt,
			ThreadDeath, Pausable {
		try {
			result = execute1();

		} catch (NotImplemented e) {
			log.log(Level.SEVERE, "[fail] exiting "+self_handle(), e);
			result = e.reason();
			death[0] = e;
			
		} catch (ErlangException e) {
			log.log(Level.FINE, "[erl] exiting "+self_handle(), e);
			last_exception = e;
			result = e.reason();
			death[0] = e;

		} catch (ErlangExitSignal e) {
			log.log(Level.FINE, "[signal] exiting "+self_handle(), e);
			result = e.reason();
			death[0] = e;
			
		} catch (ErlangHalt e) {
			throw e;

		} catch (Throwable e) {

			log.log(Level.SEVERE, "[java] exiting "+self_handle()+" with: ", e);

			ESeq erl_trace = ErlangError.decodeTrace(e.getStackTrace());
			ETuple java_ex = ETuple.make(am_java_exception, EString
					.fromString(ERT.describe_exception(e)));

			result = ETuple.make(java_ex, erl_trace);
			death[0] = e;

		} finally {
            set_state_to_done_and_wait_for_stability();
		}
		
		if (result != am_normal && monitors.isEmpty() && has_no_links() && !(death[0] instanceof ErlangExitSignal)) {
				
				EFun fun = EModuleManager.resolve(new FunID(am_error_logger, am_info_report, 1));
				
				String msg = "Process " +self_handle()+ " exited abnormally without links/monitors\n"
					+ "exit reason was: " + result + "\n"
					+ (death[0] == null ? "" : ERT.describe_exception(death[0]));
				
				try {
					fun.invoke(this, new EObject[] { EString.fromString(msg) });
				} catch (ErlangHalt e) {
					throw e;
				} catch (ThreadDeath e) {
					throw e;
				} catch (Throwable e) {
					System.err.println(msg);
					e.printStackTrace();
					// ignore //
				}

		}

	//	System.err.println("task "+this+" exited with "+result);
		
		do_proc_termination(result);
	}

	private EObject execute1() throws Pausable {
		EObject result;
		this.check_exit();

        set_state(STATE.RUNNING);

	hibernate_loop:
		while (true) {
			try {
				while (this.tail.go(this) == TAIL_MARKER) {
					/* skip */
				}
				break hibernate_loop;
			} catch (ErjangHibernateException e) {
				// noop, live = true //
			}

			mbox_wait();
		}

		result = am_normal;
		return result;
	}

    public boolean add_exit_hook(ExitHook hook) {
        int ps = exit_action_mutator_lock();
        try {
            if (ps == STATE.DONE.ordinal()) return false; // Too late.
            exit_hooks.add(hook);
            return true;
        } finally {
            exit_action_mutator_unlock();
        }
    }

    public boolean remove_exit_hook(ExitHook hook) {
        int ps = exit_action_mutator_lock();
        try {
            if (ps == STATE.DONE.ordinal()) return false; // Too late.
            exit_hooks.remove(hook);
            return true;
        } finally {
            exit_action_mutator_unlock();
        }
    }


    /*--------- Convenience --------------------------------*/

    /* (non-Javadoc)
      * @see kilim.Task#toString()
      */
	@Override
	public String toString() {
		return self.toString() + super.toString() +
			"::" + spawn_mod + ":" + spawn_fun + "/" + spawn_args;
	}


    /*==================== Globals - public interface ===============*/

	public static ESeq processes() {
		ESeq res = ERT.NIL;
		for (EProc proc : all_tasks.values()) {
			if (proc.is_alive_dirtyread()) {
				res = res.cons(proc.self_handle());
			}
		}
		return res;
	}
	
	public static int process_count() {
		int count = 0;
		for (EProc proc : all_tasks.values()) {
			if (proc.is_alive_dirtyread()) {
				count += 1;
			}
		}
		return count;
	}


	public static EInternalPID find(int id, int serial) {
		int key = (serial << 15) | (id & 0x7fff);
		EProc task = all_tasks.get(key);
		if (task != null) return task.self_handle();
		return null;
	}



    /*==================== Globals - internal ===============*/

    static {
		if (ErjangConfig.getBoolean("erjang.dump_on_exit"))
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				log.warning("===== LIVE TASKS UPON EXIT");
				for (EProc task : all_tasks.values()) {
					
					log.warning("==" + task);
					log.warning(task.fiber.toString());
				}
				log.warning("=====");				
			}
		});
	}

    public ERT.TraceFlags get_trace_flags() {
        if (trace_flags == null)
            return ERT.global_trace_flags;
        return trace_flags;
    }

    public ERT.TraceFlags get_own_trace_flags() {
        if (trace_flags == null)
            trace_flags = ERT.global_trace_flags.clone();
        return trace_flags;
    }

}
